package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 REMEDIATION: Hard-coded database credentials removed.
    // DB_USER ("admin") and DB_PASS ("Resort$Pass#2019!") previously embedded
    // in source code are now retrieved at runtime from AWS Secrets Manager via
    // the AwsSecretsManagerConfig bean, preventing credential exposure in
    // version control and enabling automatic rotation without redeployment.
    @Autowired
    private AwsSecretsManagerConfig.DbCredentials dbCredentials;

    // cr-java-0090 REMEDIATION: File-based authentication replaced with
    // AWS Secrets Manager and Amazon Cognito.
    // Previously, authentication credentials and user data were stored in
    // local files, which does not scale horizontally and creates security and
    // consistency issues in distributed cloud environments.
    // CognitoAuthService delegates all user identity management to Amazon
    // Cognito User Pools (authenticate, register, look up users) and retrieves
    // the Cognito App Client Secret from AWS Secrets Manager at runtime,
    // providing centralized, encrypted, and auditable authentication with
    // built-in user lifecycle management.
    @Autowired
    private CognitoAuthService cognitoAuthService;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    @Value("${app.payment.endpoint:http://10.0.1.45:9090/payments/charge}")
    private String paymentApi; // cr-java-0021, cr-java-0088

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
        // DB host is now sourced from AWS Secrets Manager (cr-java-0069 remediation)
        booking.put("dbHost", dbCredentials.getHost());
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
     * cr-java-0090 REMEDIATION: Authenticates a guest using Amazon Cognito
     * instead of file-based credential lookup.
     *
     * <p>Previously, user credentials were read from a local file, which does
     * not scale in distributed cloud environments. This method delegates
     * authentication to {@link CognitoAuthService}, which uses the
     * ADMIN_USER_PASSWORD_AUTH flow against the configured Cognito User Pool.
     * The Cognito App Client Secret is retrieved from AWS Secrets Manager at
     * runtime, so no credentials are stored in local files or source code.</p>
     *
     * @param username the guest's Cognito username (typically their email)
     * @param password the guest's password
     * @return a map containing JWT tokens ({@code idToken}, {@code accessToken},
     *         {@code refreshToken}) on success, or an {@code error} key on failure
     */
    public Map<String, String> authenticateGuest(String username, String password) {
        // cr-java-0090: Delegate to CognitoAuthService — no local file access.
        return cognitoAuthService.authenticateUser(username, password);
    }

    /**
     * cr-java-0090 REMEDIATION: Registers a new guest user in Amazon Cognito
     * instead of writing user data to a local file.
     *
     * @param username     the desired username (typically the guest's email)
     * @param email        the guest's email address
     * @param tempPassword a temporary password; Cognito will prompt the user to change it
     * @return a map with {@code userId} and {@code status} on success,
     *         or an {@code error} key on failure
     */
    public Map<String, String> registerGuest(String username, String email, String tempPassword) {
        // cr-java-0090: Delegate to CognitoAuthService — no local file write.
        return cognitoAuthService.registerUser(username, email, tempPassword);
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
