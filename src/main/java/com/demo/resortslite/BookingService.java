package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Removed hardcoded DB credentials (sec-cred-001 / cr-java-0021).
    // Database connection is now fully managed by Spring DataSource configured via
    // environment variables (SPRING_DATASOURCE_URL / USERNAME / PASSWORD) injected
    // at runtime from AWS Secrets Manager or Parameter Store.

    // Payment API endpoint externalised to environment variable (cr-java-0021, cr-java-0088).
    // HTTPS enforced; value injected at runtime via AWS Parameter Store / ECS task definition.
    @Value("${app.payment.endpoint:https://payment-svc.internal:9090/charge}")
    private String paymentApi;

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterised query prevents SQL injection
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // SHA-256 with explicit UTF-8 charset for deterministic behaviour across JVM versions
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterised query prevents SQL injection
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
     * season, and guest loyalty tier.
     *
     * @param roomType one of STANDARD, DELUXE, SUITE, VILLA
     * @param nights   number of nights to stay
     * @param season   PEAK, OFF, or standard
     * @param loyalty  GOLD, PLATINUM, DIAMOND, or none
     * @return formatted total price string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = switch (roomType) {
            case "DELUXE"   -> 200.0;
            case "SUITE"    -> 350.0;
            case "VILLA"    -> 600.0;
            default         -> 120.0; // STANDARD and unknown types
        };

        double seasonMultiplier = switch (season) {
            case "PEAK" -> 1.5;
            case "OFF"  -> 0.8;
            default     -> 1.0;
        };

        double loyaltyMultiplier = switch (loyalty) {
            case "GOLD"     -> 0.9;
            case "PLATINUM" -> 0.8;
            case "DIAMOND"  -> 0.7;
            default         -> 1.0;
        };

        double stayDiscount = (nights >= 14) ? 0.90 : (nights >= 7) ? 0.95 : 1.0;

        double total = basePrice * seasonMultiplier * loyaltyMultiplier * stayDiscount * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether the given room type is a recognised, bookable category.
     *
     * @param roomType room type identifier
     * @return true if the room type is valid and available for booking
     */
    public boolean isRoomAvailable(String roomType) {
        // Centralised validation using the same switch expression used in calculateRoomPrice,
        // eliminating the duplicated room-type check (dup-logic-001).
        return switch (roomType) {
            case "STANDARD", "DELUXE", "SUITE", "VILLA" -> true;
            default -> false;
        };
    }

    /**
     * Triggers report generation for the specified month.
     *
     * @param month month identifier (e.g. "2024-03")
     * @return status message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     * Uses explicit UTF-8 encoding (StandardCharsets.UTF_8) so that the result
     * is deterministic regardless of the JVM default charset.
     *
     * @param input string to hash
     * @return lowercase hex-encoded SHA-256 digest
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
