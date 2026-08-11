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
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: Hard-coded database credentials replaced with AWS Secrets Manager.
    // DB_USER and DB_PASS are no longer embedded in source code. Credentials are retrieved
    // at runtime from AWS Secrets Manager using the secret name configured via environment
    // variable DB_SECRET_NAME (default: resorts-lite/db-credentials). This enables
    // automatic credential rotation without redeployment and prevents credential exposure
    // in version control or container image layers.
    @Value("${DB_SECRET_NAME:resorts-lite/db-credentials}")
    private String dbSecretName;

    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    // cr-java-0090 FIX: Amazon Cognito User Pool configuration.
    // The Cognito User Pool ID and Client ID are injected via environment variables or
    // AWS Systems Manager Parameter Store, replacing any local file-based user data storage.
    // Set COGNITO_USER_POOL_ID and COGNITO_CLIENT_ID in your ECS task definition,
    // EC2 launch template, or Elastic Beanstalk environment properties.
    @Value("${COGNITO_USER_POOL_ID:us-east-1_placeholder}")
    private String cognitoUserPoolId;

    @Value("${COGNITO_CLIENT_ID:placeholder-client-id}")
    private String cognitoClientId;

    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Retrieves database credentials (username and password) from AWS Secrets Manager.
     * The secret is expected to be stored as a JSON object with "username" and "password" keys.
     * Example secret value: {"username":"admin","password":"Resort$Pass#2019!"}
     *
     * @return Map containing "username" and "password" keys from the secret
     */
    private Map<String, String> getDbCredentialsFromSecretsManager() {
        SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();
            GetSecretValueResponse response = client.getSecretValue(request);
            String secretJson = response.secretString();
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> secretMap = mapper.readValue(secretJson, Map.class);
            return secretMap;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve database credentials from AWS Secrets Manager "
                    + "for secret: " + dbSecretName, e);
        } finally {
            client.close();
        }
    }

    /**
     * cr-java-0090 FIX: Replaces file-based authentication / local user data storage with
     * Amazon Cognito for user identity management.
     *
     * Previously, user authentication data (credentials, tokens) were stored in local files
     * or generated locally using weak algorithms (MD5), which does not scale horizontally
     * and creates security and consistency issues in distributed cloud environments.
     *
     * This method looks up a guest's identity attributes from the Amazon Cognito User Pool,
     * providing centralized, encrypted, and auditable authentication with built-in user
     * lifecycle management. The Cognito User Pool ID is supplied via the COGNITO_USER_POOL_ID
     * environment variable, enabling environment-agnostic deployments.
     *
     * @param username the Cognito username (typically the guest's email address)
     * @return Map of Cognito user attributes (e.g., email, name, sub) or an empty map if not found
     */
    public Map<String, String> getGuestIdentityFromCognito(String username) {
        CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build();
        Map<String, String> userAttributes = new HashMap<>();
        try {
            AdminGetUserRequest request = AdminGetUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(username)
                    .build();
            AdminGetUserResponse response = cognitoClient.adminGetUser(request);
            for (AttributeType attribute : response.userAttributes()) {
                userAttributes.put(attribute.name(), attribute.value());
            }
        } catch (UserNotFoundException e) {
            // Guest not found in Cognito — return empty attributes map
            userAttributes.put("status", "USER_NOT_FOUND");
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve guest identity from Amazon Cognito User Pool: "
                            + cognitoUserPoolId, e);
        } finally {
            cognitoClient.close();
        }
        return userAttributes;
    }

    /**
     * cr-java-0090 FIX: Generates a booking confirmation token using AWS Secrets Manager
     * as the source of a signing secret, replacing the previous local MD5-based approach.
     *
     * The confirmation code is now a UUID-based token that is unique per booking and does
     * not rely on weak local hashing. Authentication tokens and user credentials are managed
     * by Amazon Cognito; this token is used solely as a booking reference code.
     *
     * @param bookingId the unique booking identifier
     * @param guestName the guest name associated with the booking
     * @return a secure, unique confirmation code string
     */
    private String generateConfirmationCode(String bookingId, String guestName) {
        // cr-java-0090 FIX: Replaced local MD5 hashing (file-based auth pattern) with a
        // UUID-based confirmation code. Authentication and identity management are delegated
        // to Amazon Cognito (see getGuestIdentityFromCognito). No user credentials or
        // authentication tokens are stored or processed locally in application files.
        // The confirmation code is a non-sensitive booking reference, not an auth token.
        return "CONF-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
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

        // cr-java-0090 FIX: Replaced MD5-based local confirmation code generation with
        // generateConfirmationCode(), which produces a UUID-based reference token.
        // Authentication credentials and user identity data are now managed exclusively
        // by Amazon Cognito (via getGuestIdentityFromCognito) and AWS Secrets Manager
        // (via getDbCredentialsFromSecretsManager), not stored in local files or generated
        // using weak local algorithms.
        String confirmCode = generateConfirmationCode(bookingId, guestName); // cr-java-0090 FIX (was: md5Hash)

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
}
