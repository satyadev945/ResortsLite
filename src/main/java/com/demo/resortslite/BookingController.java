package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// Updated: javax.servlet migrated to jakarta.servlet (Spring Boot 3.x / Jakarta EE 10)
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Fix cr-java-0067: In-memory static cache removed.
    // For distributed/cloud deployments use an external cache (e.g., Redis via Spring Cache)
    // so all EC2/ECS instances share the same cache state.

    // Fix cr-java-0021 / cr-java-0088: Inventory endpoint externalised to env var.
    // Injected from application.properties (app.inventory.endpoint).
    @Value("${app.inventory.endpoint:https://inventory-svc/rooms}")
    private String inventoryEndpoint;

    // Fix czr-java-001: Report base path externalised to env var.
    // Injected from application.properties (app.report.base-path).
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {
        // Fix cr-java-0065: HttpSession parameter removed from method signature.
        // Booking state is persisted in the database (PostgreSQL) and returned in the
        // response body — no server-side session storage. Stateless design is required
        // for horizontal scaling across multiple EC2/ECS instances behind an ALB.

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Fix cr-java-0065: session.setAttribute calls removed.
        // Fix cr-java-0067: Static in-memory bookingCache removed.
        // Booking is persisted to PostgreSQL via BookingService — no instance-local state.

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        // Fix cr-java-0065: HttpSession parameter removed.
        // Guest information is retrieved from the database, not from session memory.
        // This ensures consistent results regardless of which instance handles the request.

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Fix cr-java-0088: Plain HTTP URL replaced with HTTPS endpoint injected from env var.
        // inventoryEndpoint is set via app.inventory.endpoint in application.properties.

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // Fix czr-java-001: Hardcoded absolute path replaced with env-var-backed base path.
        // reportBasePath is injected from app.report.base-path (application.properties).
        // In container/cloud environments, set APP_REPORT_BASE_PATH to an S3 path or
        // a mounted volume path — the container image itself has no /var/legacy/reports.
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
