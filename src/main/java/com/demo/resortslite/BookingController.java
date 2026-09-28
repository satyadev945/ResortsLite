package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private static final Logger logger = LoggerFactory.getLogger(BookingController.class);

    @Autowired
    private BookingService bookingService;
    
    // IMPROVED: Externalized inventory endpoint to configuration
    @Value("${app.inventory.endpoint:https://inventory-service.internal:8081/rooms/available}")
    private String inventoryUrl;
    
    // IMPROVED: Externalized report path to configuration
    @Value("${app.report.path:/tmp/reports/}")
    private String reportPath;

    // NOTE cr-java-0067 [Cloud Compatibility]: In-memory cache without TTL
    // breaks horizontal scaling — cache is instance-local, invisible to other EC2 instances
    private static final Map<String, Object> bookingCache = new HashMap<>(); // cr-java-0067

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        logger.info("Received booking request for guest: {}, room type: {}", guestName, roomType);

        try {
            Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

            // VIOLATION cr-java-0065 [Cloud Compatibility / Mandatory]: Booking state stored in
            // HTTP session memory. AWS ALB distributes requests across EC2 instances — session
            // data on instance A is invisible to instance B. Auto-scaling and failover breaks.
            session.setAttribute("lastBooking", booking); // cr-java-0065
            session.setAttribute("guestName", guestName); // cr-java-0065

            bookingCache.put((String) booking.get("bookingId"), booking);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "confirmed");
            response.put("booking", booking);
            
            logger.info("Booking created successfully: {}", booking.get("bookingId"));
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            logger.error("Invalid booking request: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
            
        } catch (Exception e) {
            logger.error("Failed to create booking", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Internal server error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @GetMapping("/status/{bookingId}")
    public ResponseEntity<Map<String, Object>> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        logger.info("Retrieving booking status for: {}", bookingId);

        try {
            // VIOLATION cr-java-0065 [Cloud Compatibility / Mandatory]: Reading business state
            // from HTTP session — will return null on any other instance in the cluster.
            String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065

            Map<String, Object> result = new HashMap<>();
            result.put("bookingId", bookingId);
            result.put("sessionGuest", lastGuest);
            result.put("details", bookingService.getBookingById(bookingId));
            
            logger.info("Booking status retrieved successfully: {}", bookingId);
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("Failed to retrieve booking status for: {}", bookingId, e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to retrieve booking: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @GetMapping("/availability")
    public ResponseEntity<Map<String, Object>> checkAvailability(@RequestParam String roomType) {
        logger.info("Checking availability for room type: {}", roomType);
        
        try {
            // IMPROVED: Using externalized configuration for inventory endpoint
            // Default uses HTTPS for cloud compatibility
            
            Map<String, Object> response = new HashMap<>();
            response.put("roomType", roomType);
            response.put("inventoryEndpoint", inventoryUrl);
            response.put("available", bookingService.isRoomAvailable(roomType));
            
            logger.info("Availability check completed for room type: {}", roomType);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Failed to check availability for room type: {}", roomType, e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to check availability: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @GetMapping("/report/download")
    public ResponseEntity<Map<String, Object>> downloadReport(@RequestParam String month) {
        logger.info("Generating report for month: {}", month);
        
        try {
            // IMPROVED: Using externalized configuration for report path
            // Container-friendly path that can be mounted as a volume
            String fullReportPath = reportPath + month + "_bookings.pdf";
            
            Map<String, Object> response = new HashMap<>();
            response.put("reportPath", fullReportPath);
            response.put("message", bookingService.generateReport(month));
            
            logger.info("Report generation completed for month: {}", month);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Failed to generate report for month: {}", month, e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Failed to generate report: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
