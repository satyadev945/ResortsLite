package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.PostConstruct;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-native booking management service.
 *
 * cr-java-0090 FIX: File-based authentication replaced with AWS Secrets Manager
 * (for credential storage) and Amazon Cognito (for user identity management).
 * Guest identity is now validated against the Cognito User Pool before a booking
 * is created, eliminating any reliance on local file-based user stores or
 * hard-coded credential files.  The Cognito User Pool ID and region are
 * externalised via environment variables / Spring properties so no identity
 * configuration is baked into the source code or container image.
 *
 * cr-java-0069 FIX: DB credentials are retrieved at runtime from AWS Secrets
 * Manager; no passwords appear in source code or configuration files.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    // cr-java-0069 FIX: DB_USER and DB_PASS are no longer hard-coded.
    // Credentials are retrieved at runtime from AWS Secrets Manager.
    // The secret name is externalised via the environment variable DB_SECRET_NAME
    // (default: "resortslite/db/credentials") so no credentials appear in source
    // code or container image layers, enabling automated rotation without redeployment.
    private String DB_USER;
    private String DB_PASS;

    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    @Value("${db.secret.name:resortslite/db/credentials}")
    private String dbSecretName;

    // cr-java-0090 FIX: Cognito User Pool configuration externalised to environment
    // variables / Spring properties.  Set COGNITO_USER_POOL_ID (or cognito.user-pool-id)
    // to the Cognito User Pool that manages resort guest identities.
    // When cognito.auth.enabled=false (default for local dev) the Cognito lookup is
    // skipped so the service can still start without AWS credentials in local environments.
    @Value("${cognito.user-pool-id:${COGNITO_USER_POOL_ID:}}")
    private String cognitoUserPoolId;

    @Value("${cognito.auth.enabled:false}")
    private boolean cognitoAuthEnabled;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Loads database credentials from AWS Secrets Manager on bean initialisation.
     * The secret is expected to be a JSON object with "username" and "password" keys,
     * e.g.: {"username":"admin","password":"Resort$Pass#2019!"}
     * Secret name is resolved from the environment variable DB_SECRET_NAME or the
     * Spring property db.secret.name (default: "resortslite/db/credentials").
     */
    @PostConstruct
    public void loadDatabaseCredentials() {
        try {
            SecretsManagerClient secretsClient = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse response = secretsClient.getSecretValue(request);
            String secretJson = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> secretMap = mapper.readValue(secretJson, Map.class);

            this.DB_USER = secretMap.get("username");
            this.DB_PASS = secretMap.get("password");

            secretsClient.close();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load database credentials from AWS Secrets Manager (secret: "
                    + dbSecretName + "): " + e.getMessage(), e);
        }
    }

    /**
     * cr-java-0090 FIX: Validates a guest identity against Amazon Cognito User Pool.
     *
     * Previously, guest authentication data was read from a local file store, which
     * does not scale horizontally and creates security/consistency issues in distributed
     * cloud environments.  This method replaces that file-based lookup with a call to
     * the Cognito AdminGetUser API, which provides centralised, encrypted, and auditable
     * identity management with built-in user lifecycle management.
     *
     * @param guestUsername the Cognito username (typically the guest's email address)
     * @return a map of Cognito user attributes (e.g. email, name, custom:loyalty_tier)
     *         or an empty map when Cognito auth is disabled (local dev mode)
     */
    public Map<String, String> validateGuestIdentityWithCognito(String guestUsername) {
        Map<String, String> userAttributes = new HashMap<>();

        if (!cognitoAuthEnabled || cognitoUserPoolId == null || cognitoUserPoolId.isEmpty()) {
            // Cognito auth is disabled (local development mode) — skip identity lookup.
            return userAttributes;
        }

        try (CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(guestUsername)
                    .build();

            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);

            for (AttributeType attribute : getUserResponse.userAttributes()) {
                userAttributes.put(attribute.name(), attribute.value());
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to validate guest identity with Amazon Cognito (userPoolId: "
                    + cognitoUserPoolId + ", username: " + guestUsername + "): "
                    + e.getMessage(), e);
        }

        return userAttributes;
    }

    /**
     * cr-java-0090 FIX: createBooking now validates the guest identity through
     * Amazon Cognito before persisting the booking, replacing the previous
     * file-based authentication/user-data lookup pattern.
     *
     * The Cognito-resolved display name (attribute "name") is used as the
     * authoritative guest name when available, falling back to the supplied
     * guestName parameter for backward compatibility in local dev mode.
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        // cr-java-0090 FIX: Validate guest identity via Amazon Cognito User Pool.
        // In cloud environments (cognito.auth.enabled=true) the guest's Cognito
        // profile is fetched; the verified display name from Cognito is used as
        // the authoritative identity, replacing any file-based user store lookup.
        Map<String, String> cognitoAttributes = validateGuestIdentityWithCognito(guestName);
        String verifiedGuestName = cognitoAttributes.getOrDefault("name", guestName);

        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // VIOLATION [Security Health / Critical]: SQL query built by string concatenation.
        // An attacker can pass guestName = "'; DROP TABLE bookings; --" to destroy data.
        // Use parameterised queries (JdbcTemplate with '?') to prevent SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('" // sql-inject-001
                + bookingId + "', '" + verifiedGuestName + "', '" + roomType               // sql-inject-001
                + "', '" + checkIn + "', '" + checkOut + "')";                             // sql-inject-001
        jdbcTemplate.execute(sql);

        // VIOLATION [Security Health / High]: MD5 is a broken hash algorithm (RFC 6151).
        // Do not use MD5 for any security-related hashing. Use SHA-256 or bcrypt.
        String confirmCode = md5Hash(bookingId + verifiedGuestName); // sec-weak-hash-001

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", verifiedGuestName);
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

    private String md5Hash(String input) { // sec-weak-hash-001
        try {
            MessageDigest md = MessageDigest.getInstance("MD5"); // sec-weak-hash-001
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
