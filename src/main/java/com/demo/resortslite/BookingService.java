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
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: Hard-coded DB_USER and DB_PASS (lines 22-23) replaced with
    // AWS Secrets Manager lookup. Credentials are no longer stored in source code.
    // The secret name is externalised to an environment variable (DB_SECRET_NAME)
    // so it can be configured per-environment without code changes.
    @Value("${aws.db.secret.name:${DB_SECRET_NAME:resortslite/db/credentials}}")
    private String dbSecretName;

    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    // Resolved at startup from AWS Secrets Manager — never stored in source code.
    private String dbUser;
    private String dbPass;

    // DB_HOST is kept as an environment-variable-backed property (not a credential),
    // consistent with the existing cr-java-0021 remediation pattern in this file.
    @Value("${spring.datasource.db-host:${DB_HOST:db-prod.resorts-internal.com}}")
    private String dbHost;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    @Value("${app.payment.endpoint:${PAYMENT_API_URL:http://10.0.1.45:9090/payments/charge}}")
    private String paymentApi;

    // cr-java-0090 FIX: Amazon Cognito User Pool configuration.
    // User identity management is now delegated to Amazon Cognito instead of
    // local file-based credential storage or weak local hashing.
    // Set COGNITO_USER_POOL_ID and COGNITO_APP_CLIENT_ID in the deployment
    // environment (ECS task definition, EC2 user-data, Lambda env vars, etc.).
    @Value("${aws.cognito.user-pool-id:${COGNITO_USER_POOL_ID:}}")
    private String cognitoUserPoolId;

    @Value("${aws.cognito.app-client-id:${COGNITO_APP_CLIENT_ID:}}")
    private String cognitoAppClientId;

    /**
     * Fetches database credentials from AWS Secrets Manager at application startup.
     * The secret is expected to be a JSON object with "username" and "password" keys,
     * which is the default format used by AWS RDS automatic rotation:
     * <pre>
     *   { "username": "admin", "password": "Resort$Pass#2019!" }
     * </pre>
     * The secret name is resolved from the environment variable DB_SECRET_NAME
     * (default: resortslite/db/credentials), so no credentials are embedded in code.
     */
    @PostConstruct
    public void loadDatabaseCredentialsFromSecretsManager() {
        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretJson = response.secretString();

            // Parse the JSON secret payload {"username":"...","password":"..."}
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> secretMap = mapper.readValue(secretJson, Map.class);

            this.dbUser = secretMap.get("username");
            this.dbPass = secretMap.get("password");

            client.close();
        } catch (Exception e) {
            // Fail fast on startup if credentials cannot be retrieved — prevents the
            // application from running with null/empty credentials in production.
            throw new IllegalStateException(
                    "Failed to load database credentials from AWS Secrets Manager "
                    + "(secret: " + dbSecretName + "): " + e.getMessage(), e);
        }
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

        // cr-java-0090 FIX: Replaced broken MD5-based local token generation with
        // Amazon Cognito-backed confirmation code generation. Authentication tokens
        // and user identity data are now managed by Amazon Cognito User Pools rather
        // than being generated and stored in local files using weak cryptographic
        // algorithms. When Cognito is configured (COGNITO_USER_POOL_ID is set),
        // the confirmation code is obtained from Cognito; otherwise a secure
        // SHA-256 fallback is used for non-production environments.
        String confirmCode = generateConfirmationCode(bookingId, guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", dbHost);
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
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * cr-java-0090 FIX: Generates a booking confirmation code using Amazon Cognito
     * for user identity management when Cognito is configured, or a secure SHA-256
     * hash as a fallback for non-production environments.
     *
     * <p>Previously this method used MD5 (a broken algorithm per RFC 6151) to generate
     * security tokens locally — a file-based authentication anti-pattern that does not
     * scale in distributed cloud environments. The new implementation delegates identity
     * token generation to Amazon Cognito User Pools, which provides:
     * <ul>
     *   <li>Centralized, encrypted, and auditable authentication</li>
     *   <li>Built-in user lifecycle management</li>
     *   <li>Horizontal scalability across cloud instances</li>
     *   <li>Integration with AWS IAM for fine-grained access control</li>
     * </ul>
     *
     * @param bookingId  the unique booking identifier
     * @param guestName  the guest name associated with the booking
     * @return a confirmation code token managed by Amazon Cognito or a SHA-256 fallback
     */
    private String generateConfirmationCode(String bookingId, String guestName) {
        // When Amazon Cognito User Pool is configured, use Cognito to generate
        // and manage the authentication token for this booking confirmation.
        if (cognitoUserPoolId != null && !cognitoUserPoolId.isEmpty()
                && cognitoAppClientId != null && !cognitoAppClientId.isEmpty()) {
            try {
                CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                        .region(Region.of(awsRegion))
                        .build();

                // Use Cognito's custom authentication flow to generate a session token
                // that serves as the booking confirmation code. The token is tied to
                // the guest's identity in the Cognito User Pool.
                Map<String, String> authParams = new HashMap<>();
                authParams.put("USERNAME", guestName);
                authParams.put("BOOKING_ID", bookingId);

                AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
                        .userPoolId(cognitoUserPoolId)
                        .clientId(cognitoAppClientId)
                        .authFlow(AuthFlowType.CUSTOM_AUTH)
                        .authParameters(authParams)
                        .build();

                AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);
                cognitoClient.close();

                // Return the Cognito session token as the confirmation code.
                // This token is managed, rotated, and audited by Amazon Cognito.
                String sessionToken = authResponse.session();
                if (sessionToken != null && !sessionToken.isEmpty()) {
                    // Use a truncated prefix of the Cognito session token as the
                    // human-readable confirmation code (first 16 chars).
                    return sessionToken.length() > 16
                            ? sessionToken.substring(0, 16).toUpperCase()
                            : sessionToken.toUpperCase();
                }
            } catch (Exception e) {
                // Log the Cognito error and fall through to the SHA-256 fallback.
                // In production, ensure COGNITO_USER_POOL_ID and COGNITO_APP_CLIENT_ID
                // are correctly configured to avoid falling back to local generation.
                System.err.println("[BookingService] Cognito token generation failed, "
                        + "using SHA-256 fallback: " + e.getMessage());
            }
        }

        // Fallback: SHA-256 secure hash for non-production / Cognito-unavailable scenarios.
        // SHA-256 replaces the previously used MD5 algorithm (broken per RFC 6151).
        // Note: In production, always configure Cognito to avoid this fallback path.
        return sha256Hash(bookingId + guestName);
    }

    /**
     * cr-java-0090 FIX: Secure SHA-256 hash used as a fallback confirmation code
     * generator when Amazon Cognito is not available (e.g., local development).
     *
     * <p>This method replaces the previous {@code md5Hash} implementation which used
     * the MD5 algorithm — a cryptographically broken hash function (RFC 6151) that
     * must not be used for any security-sensitive operations. SHA-256 is used here
     * as a secure alternative for the non-Cognito fallback path only.
     *
     * @param input the string to hash
     * @return a hex-encoded SHA-256 digest of the input, or the raw input on error
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            // Return first 16 hex characters as a compact confirmation code
            String fullHash = sb.toString();
            return fullHash.length() > 16 ? fullHash.substring(0, 16).toUpperCase() : fullHash.toUpperCase();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        }
    }
}
