package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Value("${app.database.host:localhost}")
    private String databaseHost;

    @Value("${app.payment.api:https://payments.internal/payments/charge}")
    private String paymentApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // VIOLATION [Security Health / Critical]: SQL query built by string concatenation.
        // An attacker can pass guestName = "'; DROP TABLE bookings; --" to destroy data.
        // Use parameterised queries (JdbcTemplate with '?') to prevent SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // VIOLATION [Security Health / High]: MD5 is a broken hash algorithm (RFC 6151).
        // Do not use MD5 for any security-related hashing. Use SHA-256 or bcrypt.
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", databaseHost);
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // VIOLATION [Security Health / Critical]: SQL injection via string concatenation.
        // bookingId is user-supplied input appended directly into the SQL string.
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // VIOLATION [Code Sustainability / High]: High cyclomatic complexity.
    // This method has 9+ decision branches. Automated transformation tools flag methods
    // above complexity threshold as high maintenance risk and transformation blockers.
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = getBasePrice(roomType);
        basePrice = applySeasonMultiplier(basePrice, season);
        basePrice = applyLoyaltyDiscount(basePrice, loyalty);
        basePrice = applyStayDiscount(basePrice, nights);
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    public boolean isRoomAvailable(String roomType) {
        // VIOLATION [Code Sustainability / Medium]: Duplicated validation logic.
        // Same room type validation is repeated here and in calculateRoomPrice.
        // Should be extracted to a shared RoomType enum or validator.
        return isSupportedRoomType(roomType);
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    private boolean isSupportedRoomType(String roomType) {
        return "STANDARD".equals(roomType)
                || "DELUXE".equals(roomType)
                || "SUITE".equals(roomType)
                || "VILLA".equals(roomType);
    }

    private double getBasePrice(String roomType) {
        return switch (roomType == null ? "STANDARD" : roomType.toUpperCase(Locale.ROOT)) {
            case "DELUXE" -> 200.0;
            case "SUITE" -> 350.0;
            case "VILLA" -> 600.0;
            default -> 120.0;
        };
    }

    private double applySeasonMultiplier(double basePrice, String season) {
        if ("PEAK".equalsIgnoreCase(season)) {
            return basePrice * 1.5;
        }
        if ("OFF".equalsIgnoreCase(season)) {
            return basePrice * 0.8;
        }
        return basePrice;
    }

    private double applyLoyaltyDiscount(double basePrice, String loyalty) {
        if ("GOLD".equalsIgnoreCase(loyalty)) {
            return basePrice * 0.9;
        }
        if ("PLATINUM".equalsIgnoreCase(loyalty)) {
            return basePrice * 0.8;
        }
        if ("DIAMOND".equalsIgnoreCase(loyalty)) {
            return basePrice * 0.7;
        }
        return basePrice;
    }

    private double applyStayDiscount(double basePrice, int nights) {
        if (nights >= 14) {
            return basePrice * 0.90;
        }
        if (nights >= 7) {
            return basePrice * 0.95;
        }
        return basePrice;
    }

    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return input;
        }
    }
}
