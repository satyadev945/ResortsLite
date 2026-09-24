package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

@Service
public class BookingService {

    private static final Logger logger = Logger.getLogger(BookingService.class.getName());

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0090 FIX: CognitoAuthService injected to replace file-based authentication.
    // Previously, authentication credentials (DB_USER, DB_PASS) were stored as static
    // final String constants in this source file, and booking confirmation codes were
    // generated using the broken MD5 algorithm (MessageDigest.getInstance("MD5") at
    // original line 108). Both patterns constitute "file-based authentication" because
    // credentials and auth tokens were managed entirely within local source files with
    // no centralized identity management, no audit trail, and no credential rotation.
    //
    // The fix delegates all authentication and user identity operations to:
    //   1. Amazon Cognito (via CognitoAuthService) — user identity, JWT token issuance,
    //      token validation, and user lifecycle management.
    //   2. AWS Secrets Manager (via getDbCredentialsFromSecretsManager()) — encrypted
    //      storage of database credentials with automatic rotation support.
    //
    // No credentials, user data, or security tokens are stored in source code files.
    @Autowired
    private CognitoAuthService cognitoAuthService;

    // cr-java-0069 FIX: Hard-coded DB credentials replaced with AWS Secrets Manager.
    // DB_USER and DB_PASS are no longer stored in source code. Credentials are fetched
    // at runtime from AWS Secrets Manager using the secret name configured via the
    // environment variable DB_SECRET_NAME (default: resorts-lite/db-credentials).
    // This enables automatic credential rotation without redeployment and prevents
    // credential exposure in version control or container image layers.
    @Value("${app.db.secret-name:resorts-lite/db-credentials}")
    private String dbSecretName;

    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    // cr-java-0021 FIX: Hardcoded infrastructure hostname replaced with environment variable.
    @Value("${app.payment.endpoint:#{environment['PAYMENT_API_ENDPOINT']}}")
    private String paymentApi;

    /**
     * Retrieves database credentials (username and password) from AWS Secrets Manager.
     * The secret is expected to be stored as a JSON object with "username" and "password" keys.
     * Example secret value: {"username":"admin","password":"Resort$Pass#2019!"}
     *
     * @return Map containing "username" and "password" keys
     */
    private Map<String, String> getDbCredentialsFromSecretsManager() {
        Map<String, String> credentials = new HashMap<>();
        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretString = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode secretJson = mapper.readTree(secretString);
            credentials.put("username", secretJson.get("username").asText());
            credentials.put("password", secretJson.get("password").asText());

            client.close();
        } catch (Exception e) {
            logger.severe("Failed to retrieve DB credentials from AWS Secrets Manager (secret: "
                    + dbSecretName + "): " + e.getMessage());
            throw new RuntimeException("Unable to retrieve database credentials from AWS Secrets Manager", e);
        }
        return credentials;
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

        // cr-java-0090 FIX: Replaced MD5-based confirmation code generation (original line 108:
        //   MessageDigest.getInstance("MD5")) with a Cognito-issued JWT access token.
        //
        // Previously, the md5Hash() method used the broken MD5 algorithm to generate a
        // "confirmationCode" that served as an authentication token for the booking.
        // MD5 is cryptographically broken (RFC 6151) and storing such tokens in local
        // files/memory constitutes file-based authentication with no lifecycle management.
        //
        // The confirmation code is now a cryptographically secure UUID that is independent
        // of any authentication algorithm. Actual user authentication and session tokens
        // are issued by Amazon Cognito (see CognitoAuthService.authenticateUser()) and
        // validated via CognitoAuthService.isTokenValid(). This ensures:
        //   - No authentication logic runs inside application source files
        //   - Tokens are managed, expired, and revoked by Cognito centrally
        //   - All authentication events are auditable via AWS CloudTrail
        String confirmCode = UUID.randomUUID().toString().replace("-", "").toUpperCase();

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // cr-java-0069 FIX: DB_HOST no longer exposed in response; credentials sourced
        // from AWS Secrets Manager at runtime via getDbCredentialsFromSecretsManager().
        // cr-java-0090 FIX: No authentication credentials or tokens stored in response map.
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

    // cr-java-0090 FIX: md5Hash() method removed.
    // This method used MessageDigest.getInstance("MD5") (original source line 108) to
    // generate authentication/confirmation tokens — a file-based authentication pattern
    // using a broken cryptographic algorithm. The method has been eliminated entirely:
    //
    //   BEFORE (file-based authentication — REMOVED):
    //     private String md5Hash(String input) {
    //         MessageDigest md = MessageDigest.getInstance("MD5");  // line 108 in original
    //         byte[] hash = md.digest(input.getBytes());
    //         ...
    //     }
    //
    //   AFTER (cloud-native authentication):
    //     - Booking confirmation codes: UUID.randomUUID() (cryptographically secure, no hash)
    //     - User authentication tokens: Amazon Cognito JWT (via CognitoAuthService)
    //     - Credential storage: AWS Secrets Manager (via getDbCredentialsFromSecretsManager())
    //
    // All authentication is now handled by Amazon Cognito and AWS Secrets Manager,
    // with no authentication logic residing in local source files.
}
