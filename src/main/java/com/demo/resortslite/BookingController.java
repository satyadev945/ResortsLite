package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// Updated from javax.servlet to jakarta.servlet for Java 21 / Spring Boot 3.x / Jakarta EE 10
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Fixed cr-java-0067: removed static in-memory bookingCache.
    // Instance-local caches break horizontal scaling — each EC2/ECS instance holds
    // a different view of the data. Use a distributed cache (e.g. Redis / ElastiCache)
    // if caching is required.

    // Externalised inventory endpoint — replaces hardcoded http:// URL (cr-java-0088).
    // Value is injected from environment variable / application.properties.
    @Value("${app.inventory.endpoint:https://inventory-svc.internal:8081/rooms}")
    private String inventoryEndpoint;

    // Externalised report base path — replaces hardcoded /var/legacy/reports/ (czr-java-001).
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Fixed cr-java-0065: removed session.setAttribute calls for booking state.
        // HTTP session state is instance-local; AWS ALB distributes requests across
        // instances so session data stored on instance A is invisible to instance B.
        // Booking state is now returned in the response body only; persistent state
        // should be stored in the database and retrieved by bookingId.

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // Fixed cr-java-0065: removed session.getAttribute("guestName").
        // Guest name is now retrieved from the database via bookingService,
        // ensuring consistent results regardless of which instance handles the request.

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Fixed cr-java-0088: replaced hardcoded plain-HTTP internal URL with the
        // externalised HTTPS endpoint injected from application configuration.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Fixed czr-java-001: replaced hardcoded absolute path /var/legacy/reports/
        // with the externalised reportBasePath injected from application configuration.
        // In production, set REPORT_BASE_PATH to a mounted volume path or use
        // cloud object storage (S3 / Azure Blob) for report delivery.
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
