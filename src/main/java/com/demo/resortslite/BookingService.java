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

    // NOTE: Hardcoded database credentials in source code.
    // For production deployments, use AWS Secrets Manager or environment variables.
    private static final String DB_HOST = "db-prod.resorts-internal.com";
    private static final String DB_USER = "admin";
    private static final String DB_PASS = "Resort$Pass#2019!";

    // NOTE: Hardcoded infrastructure hostname.
    // Cloud IP addresses and service endpoints change on restart/redeployment.
    // Externalise to environment variables or AWS Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge";

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Fixed: Using parameterised query to prevent SQL injection
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Fixed: Replaced MD5 (broken algorithm) with SHA-256 for confirmation code hashing
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

    public Map<String, Object> getBookingById(String bookingId) {
        // Fixed: Using parameterised query to prevent SQL injection
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
     * @param roomType the type of room (STANDARD, DELUXE, SUITE, VILLA)
     * @param nights   the number of nights
     * @param season   the season (PEAK, OFF, or standard)
     * @param loyalty  the loyalty tier (GOLD, PLATINUM, DIAMOND, or none)
     * @return formatted total price as a string
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
     * @param month the month for which to generate the report
     * @return a report generation status message
     */
    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    /**
     * Computes a SHA-256 hex digest of the given input string.
     * Replaces the previously used MD5 algorithm (broken per RFC 6151).
     *
     * @param input the string to hash
     * @return hex-encoded SHA-256 digest
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
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
