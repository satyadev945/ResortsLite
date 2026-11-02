package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    private final JdbcTemplate jdbcTemplate;
    private final String paymentApi;
    private final String inventoryEndpoint;
    private final String reportBasePath;

    public BookingService(
            JdbcTemplate jdbcTemplate,
            @Value("${app.payment.endpoint:https://payment-service.internal/charge}") String paymentApi,
            @Value("${app.inventory.endpoint:https://inventory-service.internal/rooms}") String inventoryEndpoint,
            @Value("${app.report.base-path:${java.io.tmpdir}/resort-reports}") String reportBasePath) {
        this.jdbcTemplate = jdbcTemplate;
        this.paymentApi = paymentApi;
        this.inventoryEndpoint = inventoryEndpoint;
        this.reportBasePath = reportBasePath;
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                             String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);

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

    public Map<String, Object> getBookingById(String bookingId) {
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = switch (roomType) {
            case "DELUXE" -> 200.0;
            case "SUITE" -> 350.0;
            case "VILLA" -> 600.0;
            default -> 120.0;
        };

        if ("PEAK".equals(season)) {
            basePrice *= 1.5;
        } else if ("OFF".equals(season)) {
            basePrice *= 0.8;
        }

        if ("GOLD".equals(loyalty)) {
            basePrice *= 0.9;
        } else if ("PLATINUM".equals(loyalty)) {
            basePrice *= 0.8;
        } else if ("DIAMOND".equals(loyalty)) {
            basePrice *= 0.7;
        }

        if (nights >= 14) {
            basePrice *= 0.90;
        } else if (nights >= 7) {
            basePrice *= 0.95;
        }

        double total = basePrice * nights;
        return String.format(Locale.ROOT, "%.2f", total);
    }

    public boolean isRoomAvailable(String roomType) {
        return switch (roomType) {
            case "STANDARD", "DELUXE", "SUITE", "VILLA" -> true;
            default -> false;
        };
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    public String getInventoryEndpoint() {
        return inventoryEndpoint;
    }

    public Path resolveReportPath(String month) {
        return Paths.get(reportBasePath, month + "_bookings.pdf");
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
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }
}
