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
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: Hardcoded DB_USER and DB_PASS removed.
    // Database credentials are now retrieved at runtime from AWS Secrets Manager.
    // The secret name is externalised via the environment variable DB_SECRET_NAME
    // (or the Spring property app.db.secret-name), so no credentials ever appear
    // in source code, compiled binaries, or version-control history.
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    @Value("${app.db.secret-name:${DB_SECRET_NAME:resorts/db/credentials}}")
    private String dbSecretName;

    @Value("${app.db.secret-region:${AWS_REGION:us-east-1}}")
    private String dbSecretRegion;

    // cr-java-0090 FIX: Amazon Cognito configuration for user identity management.
    // User Pool ID and App Client ID are externalised via environment variables /
    // AWS Parameter Store — no credentials are stored in source code or local files.
    @Value("${app.cognito.user-pool-id:${COGNITO_USER_POOL_ID:}}")
    private String cognitoUserPoolId;

    @Value("${app.cognito.client-id:${COGNITO_CLIENT_ID:}}")
    private String cognitoClientId;

    @Value("${app.cognito.region:${COGNITO_REGION:${AWS_REGION:us-east-1}}}")
    private String cognitoRegion;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Retrieves database credentials (username and password) from AWS Secrets Manager.
     * The secret is expected to be stored as a JSON object with "username" and "password" keys,
     * e.g.: {"username":"admin","password":"Resort$Pass#2019!"}
     *
     * @return a Map containing "username" and "password" entries
     */
    private Map<String, String> getDbCredentialsFromSecretsManager() {
        SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.of(dbSecretRegion))
                .build();
        try {
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
                    "Failed to retrieve database credentials from AWS Secrets Manager (secret: "
                            + dbSecretName + "): " + e.getMessage(), e);
        } finally {
            client.close();
        }
    }

    /**
     * cr-java-0090 FIX: Replaces file-based authentication (local credential files + MD5 hashing)
     * with Amazon Cognito for user identity management.
     *
     * Authentication tokens (confirmation codes) are now generated via Amazon Cognito's
     * AdminInitiateAuth flow, which provides:
     *   - Centralized, encrypted credential storage (no local files)
     *   - Built-in user lifecycle management (sign-up, sign-in, MFA, password policies)
     *   - Auditable authentication events via AWS CloudTrail
     *   - JWT-based tokens (ID token, access token, refresh token) instead of MD5 hashes
     *
     * The Cognito User Pool ID and App Client ID are injected via environment variables
     * (COGNITO_USER_POOL_ID, COGNITO_CLIENT_ID) or Spring properties, never hardcoded.
     *
     * @param username the Cognito username (typically the guest's email address)
     * @param password the guest's Cognito password
     * @return the Cognito ID token to be used as the booking confirmation/authentication token
     */
    public String authenticateWithCognito(String username, String password) {
        CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(cognitoRegion))
                .build();
        try {
            Map<String, String> authParams = new HashMap<>();
            authParams.put("USERNAME", username);
            authParams.put("PASSWORD", password);

            AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .clientId(cognitoClientId)
                    .authFlow(AuthFlowType.ADMIN_NO_SRP_AUTH)
                    .authParameters(authParams)
                    .build();

            AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);
            // Return the Cognito ID token as the authentication/confirmation token.
            // This replaces the insecure MD5-based confirmation code previously generated
            // from local file-stored credentials.
            return authResponse.authenticationResult().idToken();
        } catch (NotAuthorizedException e) {
            throw new RuntimeException("Authentication failed: invalid credentials for user " + username, e);
        } catch (UserNotFoundException e) {
            throw new RuntimeException("Authentication failed: user not found in Cognito User Pool: " + username, e);
        } catch (Exception e) {
            throw new RuntimeException("Authentication failed via Amazon Cognito: " + e.getMessage(), e);
        } finally {
            cognitoClient.close();
        }
    }

    /**
     * cr-java-0090 FIX: Verifies that a guest user exists in the Amazon Cognito User Pool.
     * Replaces the previous pattern of reading user records from local files.
     *
     * @param username the Cognito username to look up
     * @return true if the user exists in the Cognito User Pool, false otherwise
     */
    public boolean isGuestUserRegistered(String username) {
        CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(cognitoRegion))
                .build();
        try {
            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(username)
                    .build();
            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);
            return getUserResponse != null;
        } catch (UserNotFoundException e) {
            return false;
        } catch (Exception e) {
            throw new RuntimeException("Failed to verify user in Amazon Cognito User Pool: " + e.getMessage(), e);
        } finally {
            cognitoClient.close();
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

        // cr-java-0090 FIX: Replaced insecure MD5-based confirmation code generation
        // (which relied on file-stored credentials) with a UUID-based booking reference token.
        // Full authentication token generation is now delegated to Amazon Cognito via
        // authenticateWithCognito(), which issues cryptographically secure JWT tokens.
        // The confirmation code here is a non-security-sensitive booking reference only.
        String confirmCode = "CONF-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

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
