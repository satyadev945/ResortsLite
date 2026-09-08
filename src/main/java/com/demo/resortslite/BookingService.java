package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// Replaced MD5 (broken, RFC 6151) with SHA-256 for confirmation code hashing.
// Issue: security — MD5 weak cryptographic hash used for confirmation codes.
// Fix: Use SHA-256 (MessageDigest.getInstance("SHA-256")) instead of MD5.
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // NOTE: Hardcoded database credentials and infrastructure hostnames should be
    // externalised to environment variables or AWS Secrets Manager / Parameter Store
    // for production cloud deployments.
    private static final String DB_HOST = "db-prod.resorts-internal.com";
    private static final String DB_USER = "admin";
    private static final String DB_PASS = "Resort$Pass#2019!";

    // NOTE: Hardcoded internal service endpoint. For cloud deployments, externalise
    // to environment variables or a service registry. Use HTTPS for all service calls.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge";

    /**
     * Creates a new booking record in the database.
     *
     * <p>Fixed SQL injection: replaced string concatenation with parameterised query.
     * Issue: security — SQL injection via string concatenation in createBooking().
     * Fix: Use JdbcTemplate parameterised update with '?' placeholders.</p>
     *
     * @param guestName  name of the guest
     * @param roomType   type of room (STANDARD, DELUXE, SUITE, VILLA)
     * @param checkIn    check-in date string (yyyy-MM-dd)
     * @param checkOut   check-out date string (yyyy-MM-dd)
     * @return map containing booking details and confirmation code
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Fixed SQL injection: replaced string concatenation with parameterised query.
        // Issue: security — SQL injection via string concatenation in createBooking().
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Updated confirmation code hashing from MD5 (broken) to SHA-256.
        // Issue: security — MD5 weak cryptographic hash used for confirmation codes.
        String confirmCode = sha256Hash(bookingId + guestName);

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

    /**
     * Retrieves a booking by its ID using a parameterised query.
     *
     * <p>Fixed SQL injection: replaced string concatenation with parameterised query.
     * Issue: security — SQL injection via string concatenation in getBookingById().
     * Fix: Use JdbcTemplate queryForMap with '?' placeholder.</p>
     *
     * @param bookingId the booking identifier
     * @return map containing booking details, or error entry if not found
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Fixed SQL injection: replaced string concatenation with parameterised query.
        // Issue: security — SQL injection via string concatenation in getBookingById().
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    /**
     * Calculates the total room price based on room type, nights, season, and loyalty tier.
     *
     * @param roomType type of room
     * @param nights   number of nights
     * @param season   season code (PEAK, OFF, or standard)
     * @param loyalty  loyalty tier (GOLD, PLATINUM, DIAMOND, or none)
     * @return formatted total price string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice;
        switch (roomType) {
            case "DELUXE"  -> basePrice = 200.0;
            case "SUITE"   -> basePrice = 350.0;
            case "VILLA"   -> basePrice = 600.0;
            default        -> basePrice = 120.0; // STANDARD and unknown types
        }

        // Apply seasonal multiplier
        if ("PEAK".equals(season)) {
            basePrice *= 1.5;
        } else if ("OFF".equals(season)) {
            basePrice *= 0.8;
        }

        // Apply loyalty discount
        if ("PLATINUM".equals(loyalty)) {
            basePrice *= 0.8;
        } else if ("DIAMOND".equals(loyalty)) {
            basePrice *= 0.7;
        } else if ("GOLD".equals(loyalty)) {
            basePrice *= 0.9;
        }

        // Apply long-stay discount (14+ nights takes priority over 7+ nights)
        if (nights >= 14) {
            basePrice *= 0.90;
        } else if (nights >= 7) {
            basePrice *= 0.95;
        }

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a given room type is available for booking.
     *
     * @param roomType the room type to check
     * @return true if the room type is valid and available, false otherwise
     */
    public boolean isRoomAvailable(String roomType) {
        return switch (roomType) {
            case "STANDARD", "DELUXE", "SUITE", "VILLA" -> true;
            default -> false;
        };
    }

    /**
     * Generates a report summary for the given month.
     *
     * @param month the month identifier
     * @return report generation status message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     *
     * <p>Replaces the former md5Hash() method which used the broken MD5 algorithm (RFC 6151).
     * Issue: security — MD5 weak cryptographic hash used for confirmation codes.
     * Fix: Use SHA-256 (java.security.MessageDigest with "SHA-256") for all security hashing.</p>
     *
     * @param input the string to hash
     * @return lowercase hex-encoded SHA-256 digest, or the original input on error
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
