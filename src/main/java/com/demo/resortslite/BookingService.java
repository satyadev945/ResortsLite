package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service for managing resort booking operations.
 * Externalised configuration is injected via environment variables / Spring properties.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Externalised configuration — no hardcoded credentials or hostnames
    @Value("${app.db.host:localhost}")
    private String dbHost;

    @Value("${app.payment.endpoint:https://payment-svc.internal/charge}")
    private String paymentApi;

    /**
     * Room type definitions with base prices, replacing duplicated validation logic.
     */
    enum RoomType {
        STANDARD(120.0),
        DELUXE(200.0),
        SUITE(350.0),
        VILLA(600.0);

        private final double basePrice;

        RoomType(double basePrice) {
            this.basePrice = basePrice;
        }

        public double getBasePrice() {
            return basePrice;
        }

        public static boolean isValid(String roomType) {
            for (RoomType rt : values()) {
                if (rt.name().equals(roomType)) {
                    return true;
                }
            }
            return false;
        }

        public static RoomType fromString(String roomType) {
            for (RoomType rt : values()) {
                if (rt.name().equals(roomType)) {
                    return rt;
                }
            }
            return STANDARD;
        }
    }

    /**
     * Season multipliers for pricing.
     */
    enum Season {
        PEAK(1.5),
        OFF(0.8),
        NORMAL(1.0);

        private final double multiplier;

        Season(double multiplier) {
            this.multiplier = multiplier;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public static Season fromString(String season) {
            for (Season s : values()) {
                if (s.name().equals(season)) {
                    return s;
                }
            }
            return NORMAL;
        }
    }

    /**
     * Loyalty tier multipliers for pricing.
     */
    enum LoyaltyTier {
        GOLD(0.9),
        PLATINUM(0.8),
        DIAMOND(0.7),
        NONE(1.0);

        private final double multiplier;

        LoyaltyTier(double multiplier) {
            this.multiplier = multiplier;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public static LoyaltyTier fromString(String loyalty) {
            for (LoyaltyTier lt : values()) {
                if (lt.name().equals(loyalty)) {
                    return lt;
                }
            }
            return NONE;
        }
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Fixed: SQL injection — use parameterized query with '?' placeholders
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Fixed: MD5 replaced with SHA-256
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", dbHost);
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // Fixed: SQL injection — use parameterized query with '?' placeholder
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
     * Calculates the total room price based on room type, stay duration, season, and loyalty tier.
     * Uses enum-based lookups to reduce cyclomatic complexity.
     *
     * @param roomType the type of room (STANDARD, DELUXE, SUITE, VILLA)
     * @param nights   number of nights
     * @param season   season code (PEAK, OFF, NORMAL)
     * @param loyalty  loyalty tier (GOLD, PLATINUM, DIAMOND, NONE)
     * @return formatted total price string
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = RoomType.fromString(roomType).getBasePrice();
        basePrice *= Season.fromString(season).getMultiplier();
        basePrice *= LoyaltyTier.fromString(loyalty).getMultiplier();

        // Long-stay discount
        if (nights >= 14) {
            basePrice *= 0.90;
        } else if (nights >= 7) {
            basePrice *= 0.95;
        }

        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    public boolean isRoomAvailable(String roomType) {
        // Fixed: uses shared RoomType enum instead of duplicated validation logic
        return RoomType.isValid(roomType);
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    /**
     * Hashes input using SHA-256 (replaces insecure MD5).
     *
     * @param input the string to hash
     * @return hex-encoded SHA-256 hash
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return input;
        }
    }
}
