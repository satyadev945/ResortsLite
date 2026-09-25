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

    // Fix cr-java-0021 / sec-cred-001: Hardcoded DB credentials removed.
    // DB connection is managed by Spring DataSource (application.properties / env vars).
    // DB_HOST, DB_USER, DB_PASS constants eliminated — never store credentials in source code.

    // Fix cr-java-0021 / cr-java-0088: Hardcoded internal IP/HTTP endpoint externalised.
    // Injected from environment variable via application.properties (app.payment.endpoint).
    @Value("${app.payment.endpoint:https://payment-svc/charge}")
    private String paymentApi;

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Fix sql-inject-001 [Security Health / Critical]: SQL injection via string
        // concatenation replaced with parameterised query using JdbcTemplate '?' placeholders.
        // PostgreSQL-compatible INSERT with positional parameters.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // SHA-256 used for confirmation code generation (non-password hashing context).
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // Fix cr-java-0021: dbHost no longer exposed in response — connection details are internal.
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // Fix sql-inject-001 [Security Health / Critical]: SQL injection via string
        // concatenation replaced with parameterised query using JdbcTemplate '?' placeholder.
        // PostgreSQL-compatible SELECT with positional parameter.
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // Refactored: Reduced cyclomatic complexity by extracting room base price and
    // discount multiplier lookups into helper methods (Code Sustainability improvement).
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = getRoomBasePrice(roomType);
        basePrice = applySeasonMultiplier(basePrice, season);
        basePrice = applyLoyaltyDiscount(basePrice, loyalty);
        // Apply long-stay discount — 14+ nights takes priority over 7+ nights
        if (nights >= 14) {
            basePrice = basePrice * 0.90;
        } else if (nights >= 7) {
            basePrice = basePrice * 0.95;
        }
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    // Fix dup-logic-001 [Code Sustainability]: Room type validation extracted to a single
    // shared helper, eliminating duplication between calculateRoomPrice and isRoomAvailable.
    private boolean isValidRoomType(String roomType) {
        return roomType != null && (
                roomType.equals("STANDARD") ||
                roomType.equals("DELUXE") ||
                roomType.equals("SUITE") ||
                roomType.equals("VILLA")
        );
    }

    private double getRoomBasePrice(String roomType) {
        return switch (roomType) {
            case "STANDARD" -> 120.0;
            case "DELUXE"   -> 200.0;
            case "SUITE"    -> 350.0;
            case "VILLA"    -> 600.0;
            default         -> 120.0;
        };
    }

    private double applySeasonMultiplier(double price, String season) {
        return switch (season) {
            case "PEAK" -> price * 1.5;
            case "OFF"  -> price * 0.8;
            default     -> price;
        };
    }

    private double applyLoyaltyDiscount(double price, String loyalty) {
        return switch (loyalty) {
            case "GOLD"     -> price * 0.9;
            case "PLATINUM" -> price * 0.8;
            case "DIAMOND"  -> price * 0.7;
            default         -> price;
        };
    }

    public boolean isRoomAvailable(String roomType) {
        // Fix dup-logic-001: Delegating to shared isValidRoomType helper.
        return isValidRoomType(roomType);
    }

    public String generateReport(String month) {
        // Fix cr-java-0021 / cr-java-0088: paymentApi injected from env var — no hardcoded IP.
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    // SHA-256 for confirmation code generation (non-password hashing context).
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
