package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-ready implementation.
 *
 * cr-java-0090 FIX (File-based Authentication):
 *   Authentication credentials and user data are no longer stored in local files or
 *   hardcoded in source code.  Two AWS-native services replace the previous pattern:
 *
 *   1. AWS Secrets Manager  — stores database credentials (username / password) as a
 *      JSON secret under the name resolved from the DB_SECRET_NAME environment variable
 *      (default: "resortslite/db/credentials").  The getDbCredential() helper retrieves
 *      them at runtime so no plaintext secret ever appears in source code or config files.
 *
 *   2. Amazon Cognito       — provides centralised, encrypted, and auditable user identity
 *      management.  authenticateGuest() validates guest credentials against the Cognito
 *      User Pool identified by COGNITO_USER_POOL_ID and COGNITO_CLIENT_ID environment
 *      variables.  getGuestProfile() retrieves the user's attributes from the same pool.
 *      Both methods replace any previous file-based user-store lookups.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // -----------------------------------------------------------------------
    // AWS Secrets Manager configuration
    // -----------------------------------------------------------------------

    /**
     * Name of the AWS Secrets Manager secret that holds the database credentials JSON.
     * Resolved from the DB_SECRET_NAME environment variable; falls back to the default
     * secret name used in the ResortsLite deployment.
     */
    private static final String DB_SECRET_NAME =
            System.getenv("DB_SECRET_NAME") != null
                    ? System.getenv("DB_SECRET_NAME")
                    : "resortslite/db/credentials";

    /**
     * AWS region for all SDK calls.  Resolved from the standard AWS_REGION environment
     * variable; defaults to us-east-1 for local development.
     */
    private static final String AWS_REGION =
            System.getenv("AWS_REGION") != null
                    ? System.getenv("AWS_REGION")
                    : "us-east-1";

    // -----------------------------------------------------------------------
    // Amazon Cognito configuration
    // -----------------------------------------------------------------------

    /**
     * Cognito User Pool ID — injected via the COGNITO_USER_POOL_ID environment variable.
     * Set this to the User Pool created for ResortsLite guest accounts.
     */
    private static final String COGNITO_USER_POOL_ID =
            System.getenv("COGNITO_USER_POOL_ID") != null
                    ? System.getenv("COGNITO_USER_POOL_ID")
                    : "";

    /**
     * Cognito App Client ID — injected via the COGNITO_CLIENT_ID environment variable.
     * Must match the app client configured in the User Pool above.
     */
    private static final String COGNITO_CLIENT_ID =
            System.getenv("COGNITO_CLIENT_ID") != null
                    ? System.getenv("COGNITO_CLIENT_ID")
                    : "";

    // -----------------------------------------------------------------------
    // Other externalised configuration
    // -----------------------------------------------------------------------

    /** Database host — externalised to an environment variable; no hard-coded hostname. */
    private static final String DB_HOST =
            System.getenv("DB_HOST") != null
                    ? System.getenv("DB_HOST")
                    : "";

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    // -----------------------------------------------------------------------
    // AWS Secrets Manager helper
    // -----------------------------------------------------------------------

    /**
     * Retrieves a single credential field from the AWS Secrets Manager secret.
     *
     * The secret is stored as a JSON string, e.g.:
     *   {"username":"admin","password":"<managed-by-secrets-manager>"}
     *
     * @param key  the JSON field name to retrieve ("username" or "password")
     * @return     the plaintext value for that field
     * @throws RuntimeException if the secret cannot be retrieved or parsed
     */
    private String getDbCredential(String key) {
        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.of(AWS_REGION))
                    .build();
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(DB_SECRET_NAME)
                    .build();
            GetSecretValueResponse response = client.getSecretValue(request);
            String secretString = response.secretString();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(secretString);
            return node.has(key) ? node.get(key).asText() : "";
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve database credential '" + key
                            + "' from AWS Secrets Manager (secret: " + DB_SECRET_NAME + ")", e);
        }
    }

    // -----------------------------------------------------------------------
    // Amazon Cognito helpers  (cr-java-0090 fix)
    // -----------------------------------------------------------------------

    /**
     * Authenticates a guest user against the Amazon Cognito User Pool.
     *
     * Replaces any previous file-based user-store lookup.  Credentials are validated
     * centrally by Cognito; no password or user data is stored in local files.
     *
     * @param username  the guest's Cognito username (typically their e-mail address)
     * @param password  the guest's password (never stored locally)
     * @return          the Cognito ID token on successful authentication
     * @throws NotAuthorizedException if the credentials are invalid
     * @throws RuntimeException       if the Cognito call fails for any other reason
     */
    public String authenticateGuest(String username, String password) {
        try {
            CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                    .region(Region.of(AWS_REGION))
                    .build();

            Map<String, String> authParams = new HashMap<>();
            authParams.put("USERNAME", username);
            authParams.put("PASSWORD", password);

            InitiateAuthRequest authRequest = InitiateAuthRequest.builder()
                    .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                    .clientId(COGNITO_CLIENT_ID)
                    .authParameters(authParams)
                    .build();

            InitiateAuthResponse authResponse = cognitoClient.initiateAuth(authRequest);
            return authResponse.authenticationResult().idToken();
        } catch (NotAuthorizedException e) {
            throw new RuntimeException("Authentication failed: invalid credentials for user '" + username + "'", e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to authenticate guest '" + username + "' via Amazon Cognito", e);
        }
    }

    /**
     * Retrieves a guest user's profile attributes from the Amazon Cognito User Pool.
     *
     * Replaces any previous file-based user-data lookup.  All user lifecycle management
     * (creation, update, deletion) is handled by Cognito, not by local files.
     *
     * @param username  the guest's Cognito username
     * @return          a map of Cognito user attribute names to their values
     * @throws UserNotFoundException if no such user exists in the pool
     * @throws RuntimeException      if the Cognito call fails for any other reason
     */
    public Map<String, String> getGuestProfile(String username) {
        try {
            CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                    .region(Region.of(AWS_REGION))
                    .build();

            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(COGNITO_USER_POOL_ID)
                    .username(username)
                    .build();

            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);

            Map<String, String> profile = new HashMap<>();
            getUserResponse.userAttributes().forEach(attr ->
                    profile.put(attr.name(), attr.value()));
            return profile;
        } catch (UserNotFoundException e) {
            throw new RuntimeException("Guest user '" + username + "' not found in Cognito User Pool", e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve guest profile for '" + username + "' from Amazon Cognito", e);
        }
    }

    // -----------------------------------------------------------------------
    // Booking operations
    // -----------------------------------------------------------------------

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

        // VIOLATION [Security Health / High]: MD5 is a broken hash algorithm (RFC 6151).
        // Do not use MD5 for any security-related hashing. Use SHA-256 or bcrypt.
        String confirmCode = md5Hash(bookingId + guestName); // sec-weak-hash-001

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
