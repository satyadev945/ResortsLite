package com.demo.resortslite.controller;

import com.demo.resortslite.dto.BookingWithMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/bookings")
    @PreAuthorize("hasRole('ADMIN')")
    public List<BookingWithMetadata> getAllBookings() {
        String sql = "SELECT id, guest, room, checkin, checkout, created_by, created_at, access_level FROM bookings ORDER BY created_at DESC";

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            BookingWithMetadata booking = new BookingWithMetadata();
            booking.setId(rs.getString("id"));
            booking.setGuest(rs.getString("guest"));
            booking.setRoom(rs.getString("room"));
            booking.setCheckin(rs.getString("checkin"));
            booking.setCheckout(rs.getString("checkout"));
            booking.setCreatedBy(rs.getString("created_by"));
            booking.setCreatedAt(rs.getTimestamp("created_at"));
            booking.setAccessLevel(rs.getString("access_level"));
            return booking;
        });
    }

    @GetMapping("/bookings/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> getBookingById(@PathVariable String id) {
        String sql = "SELECT id, guest, room, checkin, checkout, created_by, created_at, access_level FROM bookings WHERE id = ?";

        try {
            BookingWithMetadata booking = jdbcTemplate.queryForObject(sql, new Object[]{id}, (rs, rowNum) -> {
                BookingWithMetadata b = new BookingWithMetadata();
                b.setId(rs.getString("id"));
                b.setGuest(rs.getString("guest"));
                b.setRoom(rs.getString("room"));
                b.setCheckin(rs.getString("checkin"));
                b.setCheckout(rs.getString("checkout"));
                b.setCreatedBy(rs.getString("created_by"));
                b.setCreatedAt(rs.getTimestamp("created_at"));
                b.setAccessLevel(rs.getString("access_level"));
                return b;
            });

            // Add role permissions information
            Map<String, Object> response = new HashMap<>();
            response.put("id", booking.getId());
            response.put("guest", booking.getGuest());
            response.put("room", booking.getRoom());
            response.put("checkin", booking.getCheckin());
            response.put("checkout", booking.getCheckout());
            response.put("created_by", booking.getCreatedBy());
            response.put("created_at", booking.getCreatedAt());
            response.put("access_level", booking.getAccessLevel());

            // Add ownership metadata
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("created_by", booking.getCreatedBy() != null ? booking.getCreatedBy() : "system");
            metadata.put("created_at", booking.getCreatedAt());
            metadata.put("access_level", booking.getAccessLevel());
            metadata.put("role_permissions", "ADMIN: full access, USER: read-only");

            response.put("ownership_metadata", metadata);

            return response;

        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Booking not found: " + id);
            error.put("message", e.getMessage());
            return error;
        }
    }
}
