package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Removed in-memory static cache (cr-java-0067).
    // Instance-local caches break horizontal scaling on ECS/EKS where each node holds
    // a different view of the data. Caching should be delegated to a distributed cache
    // (e.g. Amazon ElastiCache / Redis) if required.

    // Inventory service endpoint externalised to environment variable (cr-java-0088).
    // HTTPS enforced; value injected at runtime via AWS Parameter Store / ECS task definition.
    @Value("${app.inventory.endpoint:https://inventory-svc.internal:8081/rooms}")
    private String inventoryEndpoint;

    // Report storage base path externalised to environment variable (czr-java-001).
    // Use a cloud object storage path (e.g. S3 bucket prefix) or a mounted volume path
    // injected at runtime rather than a hardcoded filesystem path.
    @Value("${app.report.base-path:${java.io.tmpdir}/reports}")
    private String reportBasePath;

    /**
     * Creates a new booking for the given guest and room details.
     *
     * @param guestName guest's full name
     * @param roomType  room category (STANDARD, DELUXE, SUITE, VILLA)
     * @param checkIn   check-in date (ISO-8601)
     * @param checkOut  check-out date (ISO-8601)
     * @return confirmation response containing booking details
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        // Removed HttpSession usage (cr-java-0065).
        // Session state stored in JVM memory is invisible to other instances behind an
        // AWS ALB. Stateless REST design is used here; if session state is required,
        // externalise it to Amazon ElastiCache (Redis) or DynamoDB.
        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the current status and details of an existing booking.
     *
     * @param bookingId unique booking identifier
     * @return booking status and detail map
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        // Removed HttpSession read (cr-java-0065).
        // Booking details are fetched directly from the database, which is the
        // authoritative source of truth and is accessible to all cluster nodes.
        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability for the requested room type.
     *
     * @param roomType room category to check
     * @return availability response including the inventory endpoint used
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // inventoryEndpoint is now injected via @Value from application.properties /
        // environment variable (cr-java-0088). HTTPS is enforced by the default value.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Returns the download path for a monthly booking report.
     *
     * @param month month identifier (e.g. "2024-03")
     * @return response containing the report path and generation status
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Report path is now constructed from an environment-variable-backed base path
        // (czr-java-001). The base path defaults to the JVM temp directory so the
        // application works in containers without a pre-existing filesystem layout.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
