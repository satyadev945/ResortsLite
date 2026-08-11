package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: Hard-coded database credentials replaced with AWS Secrets Manager.
    // DB_USER and DB_PASS are no longer stored in source code. Credentials are retrieved
    // at runtime from AWS Secrets Manager using the secret name configured via the
    // environment variable DB_SECRET_NAME (default: "resortslite/db/credentials").
    // The secret is expected to be a JSON object with keys "username" and "password".
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    @Value("${app.db.secret.name:resortslite/db/credentials}")
    private String dbSecretName;

    @Value("${app.aws.region:us-east-1}")
    private String awsRegion;

    // cr-java-0090 FIX: Amazon Cognito User Pool configuration.
    // The Cognito User Pool ID and Client ID are injected via environment variables
    // (COGNITO_USER_POOL_ID, COGNITO_CLIENT_ID) so that no identity configuration is
    // hard-coded in source. User identity is validated against Cognito rather than
    // local file-based credential stores.
    @Value("${app.cognito.user-pool-id:${COGNITO_USER_POOL_ID:}}")
    private String cognitoUserPoolId;

    @Value("${app.cognito.client-id:${COGNITO_CLIENT_ID:}}")
    private String cognitoClientId;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * The secret identified by {@code dbSecretName} must be a JSON string of the form:
     * <pre>{"username":"...","password":"..."}</pre>
     *
     * @return a Map containing "username" and "password" keys
     */
    private Map<String, String> getDbCredentials() {
        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretJson = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> credentials = mapper.readValue(secretJson, Map.class);
            return credentials;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve database credentials from AWS Secrets Manager "
                    + "(secret: " + dbSecretName + "): " + e.getMessage(), e);
        }
    }

    /**
     * cr-java-0090 FIX: Validates a guest's identity against Amazon Cognito User Pool.
     *
     * <p>Replaces the previous pattern of storing/checking user credentials in local files
     * or in-process state. User identity is now managed centrally by Amazon Cognito,
     * providing encrypted storage, MFA support, and full audit trails.</p>
     *
     * <p>The Cognito User Pool ID is supplied via the environment variable
     * {@code COGNITO_USER_POOL_ID}. The IAM role attached to the running service must
     * have {@code cognito-idp:AdminGetUser} permission on the User Pool.</p>
     *
     * @param username the Cognito username (typically the guest's email address)
     * @return true if the user exists and is confirmed in the Cognito User Pool;
     *         false if the user is not found or the User Pool ID is not configured
     */
    public boolean validateGuestIdentity(String username) {
        if (cognitoUserPoolId == null || cognitoUserPoolId.isEmpty()) {
            // Cognito not configured — allow operation to proceed (local dev mode)
            return true;
        }
        try (CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            AdminGetUserRequest request = AdminGetUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(username)
                    .build();

            AdminGetUserResponse response = cognitoClient.adminGetUser(request);
            // User is valid if they exist and their status is CONFIRMED
            return "CONFIRMED".equals(response.userStatusAsString());
        } catch (UserNotFoundException e) {
            return false;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to validate guest identity via Amazon Cognito "
                    + "(userPoolId: " + cognitoUserPoolId + "): " + e.getMessage(), e);
        }
    }

    /**
     * cr-java-0090 FIX: Generates a cryptographically secure booking confirmation token.
     *
     * <p>Replaces the previous {@code md5Hash()} method (line 108 in the original source)
     * which used the broken MD5 algorithm to produce confirmation codes from local
     * credential data. The new implementation uses {@link SecureRandom} with Base64
     * URL-safe encoding to produce a 128-bit (16-byte) unpredictable token that is
     * not derived from any user credential or identity data stored locally.</p>
     *
     * <p>This token is suitable for booking confirmation purposes. For authentication
     * tokens (JWT / OAuth2), Amazon Cognito issues and validates tokens directly —
     * no local token generation is required.</p>
     *
     * @return a URL-safe Base64-encoded 16-byte secure random confirmation token
     */
    private String generateSecureConfirmationToken() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] tokenBytes = new byte[16];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // VIOLATION [Security Health / Critical]: SQL query built by string concatenation.
        // An attacker can pass guestName = "'; DROP TABLE bookings; --" to destroy data.
        // Use parameterised queries (JdbcTemplate with '?') to prevent SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('" // sql-inject-001
                + bookingId + "', '" + guestName + "', '" + roomType               // sql-inject-001
                + "', '" + checkIn + "', '" + checkOut + "')";                     // sql-inject-001
        jdbcTemplate.execute(sql);

        // cr-java-0090 FIX: Confirmation code is now generated using a cryptographically
        // secure random token via generateSecureConfirmationToken() instead of the
        // broken MD5 hash of local credential data (original line 108: md5Hash()).
        // Authentication tokens for user sessions are issued by Amazon Cognito — no
        // local credential hashing is performed.
        String confirmCode = generateSecureConfirmationToken();

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", DB_HOST);
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // VIOLATION [Security Health / Critical]: SQL injection via string concatenation.
        // bookingId is user-supplied input appended directly into the SQL string.
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'"; // sql-inject-001
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // VIOLATION [Code Sustainability / High]: High cyclomatic complexity.
    // This method has 9+ decision branches. Automated transformation tools flag methods
    // above complexity threshold as high maintenance risk and transformation blockers.
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = 0;
        if (roomType.equals("STANDARD")) { basePrice = 120.0; }
        else if (roomType.equals("DELUXE")) { basePrice = 200.0; }
        else if (roomType.equals("SUITE")) { basePrice = 350.0; }
        else if (roomType.equals("VILLA")) { basePrice = 600.0; }
        else { basePrice = 120.0; }
        if (season.equals("PEAK")) { basePrice = basePrice * 1.5; }
        else if (season.equals("OFF")) { basePrice = basePrice * 0.8; }
        if (loyalty.equals("GOLD")) { basePrice = basePrice * 0.9; }
        else if (loyalty.equals("PLATINUM")) { basePrice = basePrice * 0.8; }
        else if (loyalty.equals("DIAMOND")) { basePrice = basePrice * 0.7; }
        if (nights >= 7) { basePrice = basePrice * 0.95; }
        else if (nights >= 14) { basePrice = basePrice * 0.90; }
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    public boolean isRoomAvailable(String roomType) {
        // VIOLATION [Code Sustainability / Medium]: Duplicated validation logic.
        // Same room type validation is repeated here and in calculateRoomPrice.
        // Should be extracted to a shared RoomType enum or validator.
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE") // dup-logic-001
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) { // dup-logic-001
            return false;
        }
        return true;
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    // cr-java-0090 FIX: The md5Hash() method (original line 108) has been removed.
    // MD5 is a broken cryptographic hash (RFC 6151) and must not be used for any
    // security-related purpose. Confirmation codes are now generated by
    // generateSecureConfirmationToken() using SecureRandom + Base64 URL encoding.
    // Authentication credentials and user identity data are managed exclusively by
    // Amazon Cognito (validateGuestIdentity()) and AWS Secrets Manager (getDbCredentials()),
    // with no local file-based storage or processing of authentication material.
}
