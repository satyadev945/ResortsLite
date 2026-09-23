package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    private static final RowMapper<Map<String, Object>> BOOKING_ROW_MAPPER = new RowMapper<>() {
        @Override
        public Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
            Map<String, Object> booking = new HashMap<>();
            booking.put("id", rs.getString("id"));
            booking.put("guest", rs.getString("guest"));
            booking.put("room", rs.getString("room"));
            booking.put("checkin", rs.getString("checkin"));
            booking.put("checkout", rs.getString("checkout"));
            return booking;
        }
    };

    private static final Map<String, Double> ROOM_BASE_PRICES = Map.of(
            "STANDARD", 120.0,
            "DELUXE", 200.0,
            "SUITE", 350.0,
            "VILLA", 600.0);

    private final JdbcTemplate jdbcTemplate;
    private final String dbHost;
    private final String paymentApi;

    public BookingService(
            JdbcTemplate jdbcTemplate,
            @Value("${app.database.host:localhost}") String dbHost,
            @Value("${app.payment.endpoint:https://payment-svc.internal/charge}") String paymentApi) {
        this.jdbcTemplate = jdbcTemplate;
        this.dbHost = dbHost;
        this.paymentApi = paymentApi;
    }

    public Map<String, Object> createBooking(String guestName, String roomType, String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String normalizedRoomType = normalizeRoomType(roomType);
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, normalizedRoomType, checkIn, checkOut);

        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", normalizedRoomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", dbHost);
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        String sql = "SELECT id, guest, room, checkin, checkout FROM bookings WHERE id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, BOOKING_ROW_MAPPER, bookingId);
        } catch (EmptyResultDataAccessException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("error", "Booking not found: " + bookingId);
            return result;
        }
    }

    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double nightlyRate = ROOM_BASE_PRICES.getOrDefault(normalizeRoomType(roomType), 120.0);
        nightlyRate *= seasonalMultiplier(season);
        nightlyRate *= loyaltyMultiplier(loyalty);
        nightlyRate *= stayLengthMultiplier(nights);
        return String.format(Locale.ROOT, "%.2f", nightlyRate * nights);
    }

    public boolean isRoomAvailable(String roomType) {
        return ROOM_BASE_PRICES.containsKey(normalizeRoomType(roomType));
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    private String normalizeRoomType(String roomType) {
        return roomType == null ? "STANDARD" : roomType.trim().toUpperCase(Locale.ROOT);
    }

    private double seasonalMultiplier(String season) {
        String normalizedSeason = season == null ? "" : season.trim().toUpperCase(Locale.ROOT);
        return switch (normalizedSeason) {
            case "PEAK" -> 1.5;
            case "OFF" -> 0.8;
            default -> 1.0;
        };
    }

    private double loyaltyMultiplier(String loyalty) {
        String normalizedLoyalty = loyalty == null ? "" : loyalty.trim().toUpperCase(Locale.ROOT);
        return switch (normalizedLoyalty) {
            case "GOLD" -> 0.9;
            case "PLATINUM" -> 0.8;
            case "DIAMOND" -> 0.7;
            default -> 1.0;
        };
    }

    private double stayLengthMultiplier(int nights) {
        if (nights >= 14) {
            return 0.90;
        }
        if (nights >= 7) {
            return 0.95;
        }
        return 1.0;
    }

    private String sha256Hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
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
