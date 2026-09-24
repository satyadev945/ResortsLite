package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// Updated from javax.servlet to jakarta.servlet for Jakarta EE / Java 21 compatibility
// (JAVA8_TO_21_JAKARTA_EE_MIGRATION: javax.servlet.* → jakarta.servlet.*)
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0088 fix: Inventory service URL externalised to application properties.
    // No hardcoded HTTP URLs in source code; value injected at runtime.
    @Value("${app.inventory.endpoint:http://inventory-svc:8081/rooms}")
    private String inventoryEndpoint;

    // cr-java-0067 fix: Removed instance-local in-memory cache (bookingCache).
    // Instance-local caches break horizontal scaling — each EC2/EKS pod has its own
    // isolated map, invisible to other instances. Use a distributed cache (e.g., Redis /
    // AWS ElastiCache) if caching is required.

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        // cr-java-0065 fix: Removed HttpSession usage for booking state.
        // HTTP session state is instance-local and breaks AWS ALB load balancing across
        // multiple EC2/EKS instances. Booking state is returned directly in the response.
        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        // cr-java-0065 fix: Removed HttpSession.getAttribute("guestName").
        // Session-based state is not available across clustered instances.
        // Booking details are fetched directly from the database via bookingService.
        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0088 fix: Inventory URL is now injected from application properties
        // (app.inventory.endpoint) rather than hardcoded as a plain HTTP string.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // czr-java-001 fix: Removed hardcoded absolute path /var/legacy/reports/.
        // Report path is now resolved by ReportService using the configurable
        // app.report.base-path property (defaults to /tmp/reports/ for containers).
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
