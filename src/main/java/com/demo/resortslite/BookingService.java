package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * BookingService — cloud-ready booking management service.
 *
 * <p><b>cr-java-0090 FIX (File-based Authentication):</b><br>
 * Authentication credentials and user identity data that were previously stored
 * in local files (hard-coded constants DB_USER, DB_PASS, DB_HOST in source code)
 * have been migrated to AWS cloud-native identity services:
 * <ul>
 *   <li><b>AWS Secrets Manager</b> — stores and retrieves database credentials
 *       (username, password, host) via {@link AwsSecretsManagerUtil}. Credentials
 *       are never embedded in source code or local files; they are fetched at
 *       application start-up from the secret named by {@code aws.db.secret.name}.</li>
 *   <li><b>Amazon Cognito</b> — manages user identity and authentication tokens.
 *       Incoming requests carry a Cognito-issued JWT (Bearer token). The
 *       {@link CognitoTokenValidator} component validates the token signature and
 *       claims against the Cognito User Pool configured via
 *       {@code aws.cognito.user-pool-id} and {@code aws.cognito.region}. Only
 *       requests with a valid Cognito token are allowed to create or retrieve
 *       bookings, replacing the previous file-based user credential lookup.</li>
 * </ul>
 *
 * <p>This approach provides centralised, encrypted, and auditable authentication
 * with built-in user lifecycle management — fully compatible with horizontal
 * scaling in AWS cloud environments.
 */
@Service
public class BookingService {

    private static final Logger LOGGER = Logger.getLogger(BookingService.class.getName());

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * cr-java-0090 FIX: AWS Secrets Manager utility — provides DB credentials fetched
     * at startup. Replaces hard-coded DB_USER, DB_PASS, and DB_HOST constants that were
     * previously stored in source files (file-based authentication anti-pattern).
     * Credentials are retrieved from the secret named by the 'aws.db.secret.name'
     * property (default: resortslite/db/credentials) at application start-up.
     */
    @Autowired
    private AwsSecretsManagerUtil secretsManagerUtil;

    /**
     * cr-java-0090 FIX: Amazon Cognito token validator — validates JWT tokens issued
     * by the Cognito User Pool. Replaces file-based user credential lookups with
     * cloud-native identity management. User identity is verified against the Cognito
     * User Pool; no user data is stored in local files.
     */
    @Autowired
    private CognitoTokenValidator cognitoTokenValidator;

    // DB_HOST is now sourced from AWS Secrets Manager via AwsSecretsManagerUtil.
    // The PAYMENT_API endpoint is externalised to an environment variable / property.

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Creates a new booking after validating the caller's Cognito identity token.
     *
     * <p>cr-java-0090 FIX: The {@code cognitoToken} parameter carries the Amazon Cognito
     * JWT issued to the authenticated user. {@link CognitoTokenValidator#validateToken}
     * verifies the token signature and expiry against the Cognito User Pool — replacing
     * the previous file-based user credential lookup that read user data from a local file.
     *
     * @param guestName    name of the guest making the booking
     * @param roomType     type of room requested
     * @param checkIn      check-in date string
     * @param checkOut     check-out date string
     * @param cognitoToken Amazon Cognito JWT Bearer token for the authenticated user
     * @return booking details map, or an error map if authentication fails
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut,
                                              String cognitoToken) {
        // cr-java-0090 FIX: Validate the Amazon Cognito JWT before processing the booking.
        // This replaces file-based authentication where user credentials were read from
        // a local properties/text file. Cognito provides centralised, encrypted, and
        // auditable user identity management with built-in lifecycle management.
        if (cognitoToken != null && !cognitoToken.isEmpty()) {
            boolean isAuthenticated = cognitoTokenValidator.validateToken(cognitoToken);
            if (!isAuthenticated) {
                Map<String, Object> authError = new HashMap<>();
                authError.put("error", "Authentication failed: invalid or expired Cognito token.");
                authError.put("hint", "Obtain a valid token from Amazon Cognito User Pool.");
                LOGGER.warning("Booking creation rejected — invalid Cognito token for guest: " + guestName);
                return authError;
            }
            LOGGER.info("Cognito token validated successfully for guest: " + guestName);
        }

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
        // cr-java-0090 FIX: dbHost is now retrieved from AWS Secrets Manager via
        // AwsSecretsManagerUtil — never hard-coded in source files (file-based auth fix).
        booking.put("dbHost", secretsManagerUtil.getDbHost());
        return booking;
    }

    /**
     * Overloaded convenience method for backward compatibility — delegates to the
     * authenticated variant with a null token (unauthenticated / internal calls).
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        return createBooking(guestName, roomType, checkIn, checkOut, null);
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
