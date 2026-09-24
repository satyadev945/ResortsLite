package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.demo.resortslite.config.AwsSecretsManagerConfig.DbCredentials;
import com.demo.resortslite.config.AwsCognitoConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-ready resort booking service.
 *
 * cr-java-0090 FIX: File-based authentication replaced with AWS Secrets Manager
 * and Amazon Cognito.
 *
 * Previously, authentication credentials and security tokens were generated using
 * MD5 (a broken hash algorithm) and stored/compared locally.  This approach does
 * not scale horizontally and creates security and consistency issues in distributed
 * cloud environments.
 *
 * The fix:
 *   1. The md5Hash() method (which produced security confirmation tokens via MD5)
 *      has been removed.
 *   2. Confirmation codes are now generated as cryptographically secure UUIDs and
 *      stored/validated through Amazon Cognito user identity management via
 *      {@link AwsCognitoConfig}.
 *   3. Authentication credentials are fetched exclusively from AWS Secrets Manager
 *      via {@link com.demo.resortslite.config.AwsSecretsManagerConfig} — no
 *      credentials are stored in source code, local files, or version control.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: Hard-coded database credentials replaced with AWS Secrets Manager.
    // Credentials are fetched at startup via AwsSecretsManagerConfig#dbCredentials() and
    // injected here — no credentials are stored in source code or version control.
    @Autowired
    private DbCredentials dbCredentials;

    // cr-java-0090 FIX: AwsCognitoConfig provides Amazon Cognito integration for
    // user identity management and secure token generation.  Authentication state
    // is managed centrally by Cognito — not in local files or in-process memory.
    @Autowired
    private AwsCognitoConfig cognitoConfig;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

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

        // cr-java-0090 FIX: Confirmation code is now generated as a cryptographically
        // secure UUID and registered with Amazon Cognito for centralised identity
        // management.  The previous MD5-based token generation (sec-weak-hash-001) has
        // been removed — MD5 is a broken algorithm (RFC 6151) and must not be used for
        // any security-related purpose.
        String confirmCode = cognitoConfig.generateSecureConfirmationCode(bookingId, guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
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
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    // cr-java-0090 FIX: The md5Hash() method has been REMOVED.
    // MD5 (MessageDigest.getInstance("MD5")) is a broken hash algorithm (RFC 6151)
    // and must not be used for security tokens or confirmation codes.
    // Secure token generation is now delegated to AwsCognitoConfig#generateSecureConfirmationCode()
    // which uses Amazon Cognito for centralised, encrypted, and auditable identity management.
}
