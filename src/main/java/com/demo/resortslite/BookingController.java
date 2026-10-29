package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for booking operations.
 * Handles booking creation, status retrieval, availability checks, and report downloads.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * Inventory service endpoint injected from environment/configuration.
     * Replaces hardcoded HTTP URL (cr-java-0088) with HTTPS and externalised config.
     */
    @Value("${app.inventory.endpoint:https://inventory-svc.internal:8081/rooms}")
    private String inventoryEndpoint;

    /**
     * Report base path injected from environment/configuration.
     * Replaces hardcoded absolute path (czr-java-001) with externalised config.
     */
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    /**
     * Creates a new booking for the given guest and room details.
     * Booking state is returned in the response body; no server-side session is used,
     * ensuring compatibility with horizontally scaled, stateless cloud deployments.
     *
     * @param guestName guest's full name
     * @param roomType  room category (STANDARD, DELUXE, SUITE, VILLA)
     * @param checkIn   check-in date (yyyy-MM-dd)
     * @param checkOut  check-out date (yyyy-MM-dd)
     * @return confirmation map containing booking details and status
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Booking state is returned in the response — no HttpSession used.
        // This ensures stateless behaviour compatible with AWS ALB and auto-scaling.
        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves the status and details of an existing booking by its ID.
     *
     * @param bookingId the unique booking identifier
     * @return map containing booking details
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        // Booking lookup is performed via the service layer — no HttpSession dependency.
        // Stateless design ensures consistent results across all instances in the cluster.
        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability for the requested room type.
     * Uses the HTTPS inventory endpoint externalised via application configuration.
     *
     * @param roomType room category to check
     * @return map containing availability status and the resolved inventory endpoint
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Inventory endpoint is injected from config (app.inventory.endpoint).
        // Uses HTTPS to comply with cloud security standards (cr-java-0088 resolved).
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Generates and returns the download path for a monthly booking report.
     * Report path is resolved from application configuration rather than a hardcoded
     * absolute filesystem path, enabling container and cloud-native deployments.
     *
     * @param month month identifier for the report (e.g. "2024-03")
     * @return map containing the resolved report path and generation status
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path resolved from injected config value (czr-java-001 resolved).
        // In production, reportBasePath should point to an S3-mounted or ephemeral path.
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
