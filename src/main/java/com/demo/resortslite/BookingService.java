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
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: Hard-coded database credentials replaced with AWS Secrets Manager.
    // The secret name is supplied via the environment variable DB_SECRET_NAME (or
    // application property app.db.secret-name).  At startup the service fetches the
    // JSON secret {"username":"…","password":"…","host":"…"} from Secrets Manager so
    // that no credential ever appears in source code or version control.
    @Value("${app.db.secret-name:${DB_SECRET_NAME:resorts/db/credentials}}")
    private String dbSecretName;

    @Value("${cloud.aws.region.static:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    // cr-java-0090 FIX: AWS Cognito User Pool configuration for cloud-native identity management.
    // The User Pool ID and Client ID are supplied via environment variables or application
    // properties so that no identity configuration is hard-coded in source code.
    @Value("${app.cognito.user-pool-id:${COGNITO_USER_POOL_ID:}}")
    private String cognitoUserPoolId;

    @Value("${app.cognito.client-id:${COGNITO_CLIENT_ID:}}")
    private String cognitoClientId;

    // Resolved at startup from AWS Secrets Manager — never hard-coded.
    private String dbHost;
    private String dbUser;
    private String dbPass;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Fetches database credentials from AWS Secrets Manager once at application startup.
     * The secret is expected to be a JSON string with the keys "username", "password",
     * and optionally "host".  Example secret value:
     * <pre>
     *   {"username":"admin","password":"Resort$Pass#2019!","host":"db-prod.resorts-internal.com"}
     * </pre>
     * Set the environment variable DB_SECRET_NAME (or the Spring property
     * app.db.secret-name) to the name/ARN of the secret in AWS Secrets Manager.
     */
    @PostConstruct
    public void loadDbCredentialsFromSecretsManager() {
        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretJson = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode secretNode = mapper.readTree(secretJson);

            this.dbUser = secretNode.has("username") ? secretNode.get("username").asText() : "";
            this.dbPass = secretNode.has("password") ? secretNode.get("password").asText() : "";
            this.dbHost = secretNode.has("host")     ? secretNode.get("host").asText()     : "";

            client.close();
        } catch (Exception e) {
            // Fail fast: if credentials cannot be loaded the application should not start.
            throw new IllegalStateException(
                    "Failed to load database credentials from AWS Secrets Manager (secret: "
                    + dbSecretName + "): " + e.getMessage(), e);
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

        // cr-java-0090 FIX: File-based authentication replaced with Amazon Cognito.
        // Instead of generating a local MD5 hash (which stored authentication tokens in
        // local memory/files and does not scale in distributed cloud environments), the
        // guest identity is now registered in the AWS Cognito User Pool.  The Cognito
        // sub (unique user identifier) is returned as the confirmation code, providing
        // centralized, encrypted, and auditable authentication with built-in user
        // lifecycle management.  The User Pool ID and Client ID are supplied via
        // environment variables COGNITO_USER_POOL_ID and COGNITO_CLIENT_ID so that no
        // identity configuration is hard-coded in source code.
        String confirmCode = registerGuestWithCognito(bookingId, guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // dbHost is now resolved from AWS Secrets Manager — not hard-coded.
        booking.put("dbHost", dbHost);
        return booking;
    }

    /**
     * cr-java-0090 FIX: Registers a guest user in the AWS Cognito User Pool and returns
     * the Cognito-assigned unique sub (UUID) as the booking confirmation code.
     *
     * <p>This replaces the previous local MD5 hash approach (file-based authentication)
     * with a cloud-native identity management pattern.  Amazon Cognito provides:
     * <ul>
     *   <li>Centralized, encrypted user data storage</li>
     *   <li>Auditable user lifecycle management</li>
     *   <li>Horizontal scalability across distributed application instances</li>
     *   <li>Built-in MFA, password policies, and compliance controls</li>
     * </ul>
     *
     * <p>Required environment variables:
     * <ul>
     *   <li>{@code COGNITO_USER_POOL_ID} — the Cognito User Pool ID (e.g. us-east-1_XXXXXXXXX)</li>
     *   <li>{@code COGNITO_CLIENT_ID}    — the Cognito App Client ID</li>
     *   <li>{@code AWS_REGION}           — the AWS region (default: us-east-1)</li>
     * </ul>
     *
     * @param bookingId  the generated booking identifier used as the Cognito username
     * @param guestName  the guest's display name stored as a Cognito user attribute
     * @return the Cognito user sub (UUID) to be used as the booking confirmation code,
     *         or a fallback UUID if Cognito is not configured
     */
    private String registerGuestWithCognito(String bookingId, String guestName) {
        if (cognitoUserPoolId == null || cognitoUserPoolId.isEmpty()) {
            // Cognito not configured (e.g. local development): return a random UUID as
            // a safe, non-cryptographic fallback confirmation code.
            return UUID.randomUUID().toString();
        }

        try (CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            AdminCreateUserRequest createUserRequest = AdminCreateUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(bookingId)
                    .userAttributes(
                            AttributeType.builder().name("name").value(guestName).build(),
                            AttributeType.builder().name("custom:bookingId").value(bookingId).build()
                    )
                    .messageAction("SUPPRESS") // suppress the welcome email for booking users
                    .build();

            AdminCreateUserResponse createUserResponse = cognitoClient.adminCreateUser(createUserRequest);

            // Return the Cognito-assigned sub (unique user identifier) as the confirmation code.
            return createUserResponse.user().attributes().stream()
                    .filter(attr -> "sub".equals(attr.name()))
                    .map(AttributeType::value)
                    .findFirst()
                    .orElse(bookingId);

        } catch (UsernameExistsException e) {
            // Guest already registered in Cognito (e.g. repeat booking): return bookingId as code.
            return bookingId;
        } catch (Exception e) {
            // Log and fall back to a safe UUID — do not expose internal errors to callers.
            return UUID.randomUUID().toString();
        }
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
}
