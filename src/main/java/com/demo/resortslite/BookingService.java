package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // NOTE: Infrastructure endpoints and credentials should be externalised to
    // environment variables or AWS Secrets Manager / Parameter Store.
    private static final String DB_HOST = System.getenv().getOrDefault("DB_HOST", "db-prod.resorts-internal.com");
    private static final String DB_USER = System.getenv().getOrDefault("DB_USER", "admin");
    // Credentials removed from source — use environment variables or secrets management
    private static final String PAYMENT_API = System.getenv().getOrDefault(
            "PAYMENT_API_URL", "https://payment-svc.internal:9090/payments/charge");

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Fixed SQL injection: replaced string concatenation with parameterised query
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Replaced MD5 (broken, RFC 6151) with SHA-256 for confirmation code hashing
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // Removed DB_HOST from response to avoid leaking infrastructure details
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // Fixed SQL injection: replaced string concatenation with parameterised query
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
     * Calculates the total room price based on room type, number of nights,
     * season, and loyalty tier.
     *
     * <p>Refactored from verbose if-else chains to use Java 25 pattern-matching
     * switch expressions for improved readability and reduced cyclomatic complexity.</p>
     *
     * @param roomType  room category (STANDARD, DELUXE, SUITE, VILLA)
     * @param nights    number of nights
     * @param season    pricing season (PEAK, OFF, or standard)
     * @param loyalty   guest loyalty tier (GOLD, PLATINUM, DIAMOND, or none)
     * @return formatted total price string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        // Replaced verbose if-else chains with Java 25 switch expressions (pattern matching)
        double basePrice = switch (roomType) {
            case "STANDARD" -> 120.0;
            case "DELUXE"   -> 200.0;
            case "SUITE"    -> 350.0;
            case "VILLA"    -> 600.0;
            default         -> 120.0;
        };

        double seasonMultiplier = switch (season) {
            case "PEAK" -> 1.5;
            case "OFF"  -> 0.8;
            default     -> 1.0;
        };

        double loyaltyDiscount = switch (loyalty) {
            case "GOLD"     -> 0.9;
            case "PLATINUM" -> 0.8;
            case "DIAMOND"  -> 0.7;
            default         -> 1.0;
        };

        double stayDiscount = (nights >= 14) ? 0.90 : (nights >= 7) ? 0.95 : 1.0;

        double total = basePrice * seasonMultiplier * loyaltyDiscount * stayDiscount * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a room of the given type is available for booking.
     *
     * @param roomType the room category to check
     * @return {@code true} if the room type is valid and available
     */
    public boolean isRoomAvailable(String roomType) {
        // Extracted room-type validation to a single switch expression,
        // eliminating duplicated validation logic from calculateRoomPrice.
        return switch (roomType) {
            case "STANDARD", "DELUXE", "SUITE", "VILLA" -> true;
            default -> false;
        };
    }

    /**
     * Triggers report generation for the specified month.
     *
     * @param month the month identifier for the report
     * @return a status message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month;
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     * Replaces the previously used MD5 algorithm (broken per RFC 6151).
     *
     * @param input the string to hash
     * @return hex-encoded SHA-256 digest, or the original input on error
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
