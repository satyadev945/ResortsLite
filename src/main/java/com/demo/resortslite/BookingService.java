package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service layer for resort booking operations.
 *
 * <p><strong>Security notes (to be addressed in a follow-up hardening sprint):</strong>
 * <ul>
 *   <li>Database credentials must be externalised to environment variables or a secrets manager.</li>
 *   <li>SQL queries use parameterised placeholders ({@code ?}) to prevent SQL injection.</li>
 *   <li>MD5 hashing is retained for confirmation-code generation only (non-security use);
 *       replace with SHA-256 / bcrypt if used for authentication.</li>
 * </ul>
 * </p>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // NOTE: Externalise credentials to environment variables / AWS Secrets Manager.
    private static final String DB_HOST = "db-prod.resorts-internal.com";
    private static final String DB_USER = "admin";
    private static final String DB_PASS = "Resort$Pass#2019!";

    // NOTE: Externalise service endpoint to application.properties / environment variable.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge";

    /**
     * Creates a new booking record using a parameterised INSERT to prevent SQL injection.
     *
     * @param guestName guest's full name
     * @param roomType  room category (STANDARD / DELUXE / SUITE / VILLA)
     * @param checkIn   check-in date string
     * @param checkOut  check-out date string
     * @return a map containing booking details and a confirmation code
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterised query — prevents SQL injection (replaces string-concatenated SQL)
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        String confirmCode = md5Hash(bookingId + guestName);

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
     * @param bookingId the booking identifier
     * @return a map of booking fields, or an error entry if not found
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterised query — prevents SQL injection (replaces string-concatenated SQL)
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
     * Calculates the total room price based on type, nights, season, and loyalty tier.
     *
     * @param roomType room category
     * @param nights   number of nights
     * @param season   pricing season (PEAK / OFF / standard)
     * @param loyalty  loyalty tier (GOLD / PLATINUM / DIAMOND / none)
     * @return formatted total price string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = switch (roomType) {
            case "STANDARD" -> 120.0;
            case "DELUXE"   -> 200.0;
            case "SUITE"    -> 350.0;
            case "VILLA"    -> 600.0;
            default         -> 120.0;
        };

        basePrice = switch (season) {
            case "PEAK" -> basePrice * 1.5;
            case "OFF"  -> basePrice * 0.8;
            default     -> basePrice;
        };

        basePrice = switch (loyalty) {
            case "GOLD"     -> basePrice * 0.9;
            case "PLATINUM" -> basePrice * 0.8;
            case "DIAMOND"  -> basePrice * 0.7;
            default         -> basePrice;
        };

        if (nights >= 14) {
            basePrice = basePrice * 0.90;
        } else if (nights >= 7) {
            basePrice = basePrice * 0.95;
        }

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a room type is valid and available.
     *
     * @param roomType the room category to check
     * @return {@code true} if the room type is recognised
     */
    public boolean isRoomAvailable(String roomType) {
        return switch (roomType) {
            case "STANDARD", "DELUXE", "SUITE", "VILLA" -> true;
            default -> false;
        };
    }

    /**
     * Triggers report generation for the given month.
     *
     * @param month the month identifier
     * @return a status message string
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    /**
     * Computes an MD5 hash of the input string.
     *
     * <p><strong>Note:</strong> MD5 is used here only for non-security confirmation codes.
     * Do NOT use MD5 for password hashing or authentication — use bcrypt / SHA-256 instead.</p>
     *
     * @param input the string to hash
     * @return hex-encoded MD5 digest, or the original input on error
     */
    private String md5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes());
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
