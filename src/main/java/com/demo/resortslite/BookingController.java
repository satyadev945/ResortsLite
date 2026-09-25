package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for booking operations.
 * Cloud-compatible: no HTTP session state, no in-memory cache, HTTPS endpoints,
 * and externalised configuration.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Externalised configuration — no hardcoded paths or endpoints
    @Value("${app.report.base-path:/tmp/reports}")
    private String reportBasePath;

    @Value("${app.inventory.endpoint:https://inventory-svc.internal/rooms}")
    private String inventoryEndpoint;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Fixed: removed HTTP session state storage (cr-java-0065) — stateless for horizontal scaling
        // Fixed: removed in-memory cache (cr-java-0067) — use Redis or external cache for cloud

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // Fixed: removed HTTP session state read (cr-java-0065) — stateless for horizontal scaling

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Fixed: HTTPS endpoint (cr-java-0088) — externalised to configuration
        String inventoryUrl = inventoryEndpoint + "/available";

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Fixed: externalised path (czr-java-001) — no hardcoded absolute file path
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
