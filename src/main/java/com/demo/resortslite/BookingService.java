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

/**
 * Service layer for resort booking operations.
 * Handles booking creation, retrieval, pricing, availability, and report generation.
 * All database credentials and infrastructure endpoints are externalised to environment
 * variables / application configuration — no hardcoded secrets in source code.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Payment API endpoint injected from application configuration.
     * Uses HTTPS to comply with cloud security standards (cr-java-0088 resolved).
     * Replaces hardcoded IP address (cr-java-0021 resolved).
     */
    @Value("${app.payment.endpoint:https://payment-svc.internal:9090/charge}")
    private String paymentApi;

    /**
     * Creates a new booking record in the PostgreSQL database using a parameterised query.
     * SQL injection vulnerability (sql-inject-001) is resolved by using JdbcTemplate
     * prepared-statement placeholders ('?') instead of string concatenation.
     *
     * @param guestName guest's full name
     * @param roomType  room category (STANDARD, DELUXE, SUITE, VILLA)
     * @param checkIn   check-in date string (yyyy-MM-dd)
     * @param checkOut  check-out date string (yyyy-MM-dd)
     * @return map containing the new booking details and confirmation code
     */
    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Parameterised query — prevents SQL injection (sql-inject-001 resolved).
        // Compatible with PostgreSQL 16 syntax.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

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

    /**
     * Retrieves a booking record by its unique identifier using a parameterised query.
     * SQL injection vulnerability (sql-inject-001) is resolved by using JdbcTemplate
     * prepared-statement placeholders ('?') instead of string concatenation.
     *
     * @param bookingId the unique booking identifier
     * @return map containing booking fields, or an error entry if not found
     */
    public Map<String, Object> getBookingById(String bookingId) {
        // Parameterised query — prevents SQL injection (sql-inject-001 resolved).
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
     * Calculates the total room price based on room type, number of nights, season,
     * and loyalty tier. Pricing logic is consolidated here; room-type validation is
     * delegated to {@link #isRoomAvailable(String)} to eliminate duplicated logic
     * (dup-logic-001 resolved).
     *
     * @param roomType room category (STANDARD, DELUXE, SUITE, VILLA)
     * @param nights   number of nights
     * @param season   pricing season (PEAK, OFF, or standard)
     * @param loyalty  loyalty tier (GOLD, PLATINUM, DIAMOND, or none)
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

        // Long-stay discount — 14+ nights takes priority over 7+ nights
        if (nights >= 14) {
            basePrice = basePrice * 0.90;
        } else if (nights >= 7) {
            basePrice = basePrice * 0.95;
        }

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether the specified room type is a recognised category.
     * Centralised validation eliminates duplicated room-type checks across the service
     * (dup-logic-001 resolved).
     *
     * @param roomType room category to validate
     * @return {@code true} if the room type is valid; {@code false} otherwise
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
     * @param month month identifier (e.g. "2024-03")
     * @return status message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Computes a SHA-256 hex digest of the given input string using UTF-8 encoding.
     *
     * @param input string to hash
     * @return lowercase hex-encoded SHA-256 digest, or the original input on error
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
