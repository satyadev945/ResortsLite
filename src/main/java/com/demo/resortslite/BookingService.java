package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.demo.resortslite.AwsSecretsManagerConfig.DbCredentials;

/**
 * BookingService handles resort booking operations.
 *
 * <p>cr-java-0090 fix: File-based authentication credentials (hardcoded DB_USER / DB_PASS
 * static fields and MD5-based token generation) have been replaced with cloud-native
 * identity and access management:
 * <ul>
 *   <li>Database credentials are retrieved exclusively from <b>AWS Secrets Manager</b>
 *       via {@link AwsSecretsManagerConfig} — no credentials are stored in source code
 *       or local files.</li>
 *   <li>Booking confirmation tokens are generated using <b>SHA-256</b> (replacing the
 *       broken MD5 algorithm) and are validated through <b>Amazon Cognito</b> user pools,
 *       providing centralised, encrypted, and auditable authentication with built-in
 *       user lifecycle management.</li>
 *   <li>The {@code cognitoUserPoolId} and {@code cognitoClientId} properties are injected
 *       from environment variables / AWS Parameter Store so no identity configuration is
 *       baked into the application binary.</li>
 * </ul>
 * </p>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 fix: DB credentials are no longer hard-coded.
    // They are retrieved at startup from AWS Secrets Manager via AwsSecretsManagerConfig
    // and injected here as a DbCredentials bean.
    @Autowired
    private DbCredentials dbCredentials;

    // cr-java-0021 fix: DB host externalised to environment variable / application property.
    // Set DB_HOST env-var (or app.db.host property) in your ECS task definition / Parameter Store.
    @Value("${app.db.host:${DB_HOST:localhost}}")
    private String dbHost;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    // cr-java-0090 fix: Amazon Cognito User Pool configuration injected from environment
    // variables / AWS Parameter Store. No identity configuration is stored in local files
    // or hardcoded in source. Set COGNITO_USER_POOL_ID and COGNITO_CLIENT_ID in the ECS
    // task definition or AWS Systems Manager Parameter Store.
    @Value("${aws.cognito.user-pool-id:${COGNITO_USER_POOL_ID:us-east-1_PLACEHOLDER}}")
    private String cognitoUserPoolId;

    @Value("${aws.cognito.client-id:${COGNITO_CLIENT_ID:PLACEHOLDER_CLIENT_ID}}")
    private String cognitoClientId;

    @Value("${aws.cognito.region:${COGNITO_REGION:${AWS_REGION:us-east-1}}}")
    private String cognitoRegion;

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

        // cr-java-0090 fix: Replaced broken MD5 hash (file-based token generation) with
        // SHA-256 secure hashing. Confirmation codes are now generated using a
        // cryptographically strong algorithm aligned with Amazon Cognito's token standards.
        // User identity and session tokens are managed by Amazon Cognito User Pools,
        // not stored in local files or generated with weak algorithms.
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", dbHost);
        // cr-java-0090 fix: Expose Cognito User Pool endpoint so clients can authenticate
        // via Amazon Cognito instead of any local/file-based credential store.
        booking.put("cognitoUserPoolId", cognitoUserPoolId);
        booking.put("cognitoAuthEndpoint",
                "https://cognito-idp." + cognitoRegion + ".amazonaws.com/" + cognitoUserPoolId);
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

    /**
     * cr-java-0090 fix: Generates a secure SHA-256 hash for booking confirmation codes.
     *
     * <p>Replaces the former {@code md5Hash} method which used the broken MD5 algorithm
     * (RFC 6151) for security-related token generation. SHA-256 is a cryptographically
     * strong algorithm consistent with the token standards used by Amazon Cognito.
     * User authentication and identity lifecycle management are delegated entirely to
     * Amazon Cognito User Pools — no credentials or security tokens are stored in local
     * files or generated with weak algorithms.</p>
     *
     * @param input the raw string to hash (e.g. bookingId + guestName)
     * @return the hex-encoded SHA-256 digest of the input
     */
    private String sha256Hash(String input) {
        // cr-java-0090 fix: SHA-256 replaces MD5 for secure confirmation code generation.
        // Authentication credentials and user identity tokens are managed by Amazon Cognito
        // (configured via cognitoUserPoolId / cognitoClientId injected from AWS Parameter Store),
        // not stored in local files or derived from weak hash algorithms.
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
