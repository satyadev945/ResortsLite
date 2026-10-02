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

    // ── Externalised configuration ────────────────────────────────────────────
    // Migrated from hardcoded DB_HOST / DB_USER / DB_PASS constants (sec-cred-001).
    // Credentials are now injected from environment variables / AWS Parameter Store.
    // The JDBC URL itself is managed by Spring DataSource (application.properties).
    @Value("${app.payment.endpoint:https://payment-svc.internal:9090/charge}")
    private String paymentApi;

    // ─────────────────────────────────────────────────────────────────────────

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Fixed sql-inject-001: replaced string-concatenated SQL with a parameterised
        // JdbcTemplate update using positional '?' placeholders.
        // An attacker can no longer inject SQL through guestName, roomType, etc.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Fixed sec-weak-hash-001: replaced broken MD5 algorithm with SHA-256.
        // MD5 is cryptographically broken (RFC 6151); SHA-256 is the minimum
        // acceptable algorithm for non-password confirmation codes.
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // Removed: booking.put("dbHost", DB_HOST) — never expose infrastructure details in responses
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // Fixed sql-inject-001: replaced string-concatenated SQL with a parameterised query.
        // bookingId is user-supplied input and must never be interpolated directly into SQL.
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
     * Calculates the total room price for a stay.
     *
     * <p>Refactored from high-cyclomatic-complexity method: extracted room base-price
     * lookup and discount multiplier logic into private helper methods to reduce
     * branching depth and improve maintainability.</p>
     */
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = resolveBasePrice(roomType);
        basePrice = applySeasonMultiplier(basePrice, season);
        basePrice = applyLoyaltyDiscount(basePrice, loyalty);
        basePrice = applyLengthOfStayDiscount(basePrice, nights);
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    /**
     * Checks whether a room of the given type is available.
     *
     * <p>Fixed dup-logic-001: room-type validation is now delegated to
     * {@link #isValidRoomType(String)}, eliminating the duplicated validation
     * that previously existed in both this method and {@code calculateRoomPrice}.</p>
     */
    public boolean isRoomAvailable(String roomType) {
        return isValidRoomType(roomType);
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Returns true only for known room types — single source of truth (fixes dup-logic-001). */
    private boolean isValidRoomType(String roomType) {
        return "STANDARD".equals(roomType)
                || "DELUXE".equals(roomType)
                || "SUITE".equals(roomType)
                || "VILLA".equals(roomType);
    }

    /** Resolves the nightly base price for a room type. */
    private double resolveBasePrice(String roomType) {
        return switch (roomType) {
            case "STANDARD" -> 120.0;
            case "DELUXE"   -> 200.0;
            case "SUITE"    -> 350.0;
            case "VILLA"    -> 600.0;
            default         -> 120.0;
        };
    }

    /** Applies peak / off-season multiplier. */
    private double applySeasonMultiplier(double price, String season) {
        return switch (season) {
            case "PEAK" -> price * 1.5;
            case "OFF"  -> price * 0.8;
            default     -> price;
        };
    }

    /** Applies loyalty-tier discount. */
    private double applyLoyaltyDiscount(double price, String loyalty) {
        return switch (loyalty) {
            case "GOLD"     -> price * 0.9;
            case "PLATINUM" -> price * 0.8;
            case "DIAMOND"  -> price * 0.7;
            default         -> price;
        };
    }

    /** Applies length-of-stay discount for extended bookings. */
    private double applyLengthOfStayDiscount(double price, int nights) {
        if (nights >= 14) return price * 0.90;
        if (nights >= 7)  return price * 0.95;
        return price;
    }

    /**
     * Computes a SHA-256 hex digest of the input string.
     *
     * <p>Replaces the previous MD5 implementation (sec-weak-hash-001).
     * SHA-256 is collision-resistant and suitable for confirmation codes.</p>
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
