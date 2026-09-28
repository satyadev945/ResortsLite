package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class BookingService {

    private static final Logger logger = LoggerFactory.getLogger(BookingService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // FIXED: Removed hardcoded credentials - these should be externalized to
    // environment variables or AWS Secrets Manager in production
    // Example: @Value("${app.db.host}") private String dbHost;
    
    // FIXED: Removed hardcoded payment API - should be externalized to configuration
    // Example: @Value("${app.payment.endpoint}") private String paymentApi;

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        // IMPROVED: Input validation to prevent null pointer exceptions
        if (guestName == null || guestName.trim().isEmpty()) {
            logger.error("Invalid guest name provided: {}", guestName);
            throw new IllegalArgumentException("Guest name cannot be null or empty");
        }
        if (roomType == null || !isValidRoomType(roomType)) {
            logger.error("Invalid room type provided: {}", roomType);
            throw new IllegalArgumentException("Invalid room type: " + roomType);
        }
        if (checkIn == null || checkOut == null) {
            logger.error("Invalid check-in or check-out dates");
            throw new IllegalArgumentException("Check-in and check-out dates cannot be null");
        }

        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        logger.info("Creating booking for guest: {}, room type: {}", guestName, roomType);

        // FIXED: Using parameterized query to prevent SQL injection
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        try {
            jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);
            logger.info("Booking created successfully: {}", bookingId);
        } catch (Exception e) {
            logger.error("Failed to create booking for guest: {}", guestName, e);
            throw new BookingException("Failed to create booking: " + e.getMessage(), "DB_ERROR", e);
        }

        // FIXED: Replaced MD5 with SHA-256 for secure hashing
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
        // IMPROVED: Input validation
        if (bookingId == null || bookingId.trim().isEmpty()) {
            logger.error("Invalid booking ID provided: {}", bookingId);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Booking ID cannot be null or empty");
            return error;
        }

        // FIXED: Using parameterized query to prevent SQL injection
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
            logger.info("Booking retrieved successfully: {}", bookingId);
        } catch (Exception e) {
            logger.error("Failed to retrieve booking: {}", bookingId, e);
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // IMPROVED: Refactored to reduce cyclomatic complexity
    // Extracted pricing logic into separate methods for better maintainability
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        // IMPROVED: Input validation
        if (roomType == null || nights <= 0) {
            logger.error("Invalid input for price calculation: roomType={}, nights={}", roomType, nights);
            return "0.00";
        }

        double basePrice = getBaseRoomPrice(roomType);
        double seasonalPrice = applySeasonalMultiplier(basePrice, season);
        double loyaltyPrice = applyLoyaltyDiscount(seasonalPrice, loyalty);
        double finalPrice = applyDurationDiscount(loyaltyPrice, nights);
        
        double total = finalPrice * nights;
        logger.debug("Calculated price for {} nights in {} room: {}", nights, roomType, total);
        return String.format("%.2f", total);
    }
    
    /**
     * Gets the base price for a room type
     */
    private double getBaseRoomPrice(String roomType) {
        return switch (roomType) {
            case "STANDARD" -> 120.0;
            case "DELUXE" -> 200.0;
            case "SUITE" -> 350.0;
            case "VILLA" -> 600.0;
            default -> 120.0;
        };
    }
    
    /**
     * Applies seasonal pricing multiplier
     */
    private double applySeasonalMultiplier(double price, String season) {
        if (season == null) {
            return price;
        }
        return switch (season) {
            case "PEAK" -> price * 1.5;
            case "OFF" -> price * 0.8;
            default -> price;
        };
    }
    
    /**
     * Applies loyalty program discount
     */
    private double applyLoyaltyDiscount(double price, String loyalty) {
        if (loyalty == null) {
            return price;
        }
        return switch (loyalty) {
            case "GOLD" -> price * 0.9;
            case "PLATINUM" -> price * 0.8;
            case "DIAMOND" -> price * 0.7;
            default -> price;
        };
    }
    
    /**
     * Applies discount based on duration of stay
     */
    private double applyDurationDiscount(double price, int nights) {
        if (nights >= 14) {
            return price * 0.90;
        } else if (nights >= 7) {
            return price * 0.95;
        }
        return price;
    }

    public boolean isRoomAvailable(String roomType) {
        // IMPROVED: Null check to prevent NPE
        if (roomType == null) {
            logger.warn("Null room type provided for availability check");
            return false;
        }
        // IMPROVED: Using a helper method to validate room types
        return isValidRoomType(roomType);
    }
    
    /**
     * Validates if a room type is supported
     */
    private boolean isValidRoomType(String roomType) {
        return "STANDARD".equals(roomType) || "DELUXE".equals(roomType) 
                || "SUITE".equals(roomType) || "VILLA".equals(roomType);
    }

    public String generateReport(String month) {
        // IMPROVED: Input validation
        if (month == null || month.trim().isEmpty()) {
            logger.error("Invalid month provided for report generation: {}", month);
            return "Error: Invalid month provided";
        }
        logger.info("Generating report for month: {}", month);
        return "Report generation triggered for: " + month;
    }

    /**
     * Generates SHA-256 hash for secure hashing (replaces deprecated MD5)
     * @param input the input string to hash
     * @return hexadecimal representation of the hash
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
        } catch (NoSuchAlgorithmException e) {
            logger.error("SHA-256 algorithm not available", e);
            return input;
        }
    }
}
