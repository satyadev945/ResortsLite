package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;
import javax.servlet.http.HttpSession;
import java.util.Map;

/**
 * Booking Controller with Distributed Session Management
 * 
 * FIXED cr-java-0065: HTTP Session State Storage
 * 
 * This controller now uses Spring Session with Redis backend (Amazon ElastiCache).
 * HttpSession objects are automatically stored in Redis instead of local memory,
 * enabling:
 * 
 * 1. Stateless application instances - no server affinity required
 * 2. Horizontal scaling - AWS ALB can distribute requests across all EC2 instances
 * 3. Session persistence - survives instance termination and auto-scaling events
 * 4. High availability - ElastiCache provides automatic failover
 * 
 * The HttpSession API remains unchanged, but Spring Session transparently:
 * - Serializes session attributes to Redis on setAttribute()
 * - Deserializes from Redis on getAttribute()
 * - Manages session lifecycle and expiration in Redis
 * 
 * Configuration: See RedisSessionConfig.java and application.properties
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIXED cr-java-0071: Externalized inventory service URL to AWS Systems Manager Parameter Store
    @Value("${app.inventory.service.url}")
    private String inventoryServiceUrl;

    /**
     * Create a new booking and store in distributed session
     * 
     * FIXED cr-java-0065: Session attributes are now stored in Amazon ElastiCache for Redis
     * instead of local HTTP session memory. This allows the session to be accessed from
     * any application instance in the cluster.
     * 
     * FIXED cr-java-0067: In-Memory Caching Without TTL
     * Replaced static HashMap cache with Amazon ElastiCache for Redis via Spring Cache abstraction.
     * BookingService now uses @Cacheable annotation with 30-minute TTL, providing distributed
     * caching across all instances with automatic expiration and memory management.
     * 
     * @param guestName Guest name
     * @param roomType Room type
     * @param checkIn Check-in date
     * @param checkOut Check-out date
     * @param session HttpSession (backed by Redis via Spring Session)
     * @return Booking confirmation response
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        // FIXED cr-java-0067: Booking creation now uses Redis-backed caching in BookingService
        // The @Cacheable annotation in BookingService.createBooking() automatically stores
        // the booking in ElastiCache with a 30-minute TTL, replacing the unbounded HashMap
        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED cr-java-0065: Session storage now uses Amazon ElastiCache for Redis
        // Spring Session automatically serializes these attributes to Redis, making them
        // available to all application instances behind the AWS Application Load Balancer.
        // No code changes required - Spring Session handles Redis operations transparently.
        session.setAttribute("lastBooking", booking); // Now stored in Redis via Spring Session
        session.setAttribute("guestName", guestName); // Now stored in Redis via Spring Session

        // FIXED cr-java-0067: Removed unbounded in-memory cache - now using Redis via @Cacheable

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Get booking status with session data from distributed storage
     * 
     * FIXED cr-java-0067: In-Memory Caching Without TTL
     * Booking retrieval now uses Amazon ElastiCache for Redis via @Cacheable annotation
     * in BookingService.getBookingById(). Cache entries expire after 30 minutes, preventing
     * unbounded memory growth and ensuring cache consistency across all instances.
     * 
     * FIXED cr-java-0065: Session attributes are retrieved from Amazon ElastiCache for Redis,
     * ensuring consistent session data regardless of which application instance handles the request.
     * 
     * @param bookingId Booking ID
     * @param session HttpSession (backed by Redis via Spring Session)
     * @return Booking status with session information
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // FIXED cr-java-0067: getBookingById() now uses Redis cache with TTL via @Cacheable
        // Cache lookups are distributed across all instances, with automatic expiration
        // FIXED cr-java-0065: Reading from distributed Redis-backed session
        // Spring Session retrieves this attribute from ElastiCache, ensuring it's available
        // even if the previous request was handled by a different EC2 instance.
        String lastGuest = (String) session.getAttribute("guestName"); // Retrieved from Redis via Spring Session

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIXED cr-java-0071: Replaced hard-coded URL with externalized configuration from AWS Systems Manager Parameter Store
        // The URL is now retrieved from Parameter Store via Spring @Value annotation
        // Configure in AWS Parameter Store: /config/resortslite/app.inventory.service.url
        String inventoryUrl = inventoryServiceUrl + "/rooms/available";

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute
        // file path. This path does not exist inside a container image. Container images
        // have their own isolated file systems — /var/legacy/reports won't be present.
        String reportPath = "/var/legacy/reports/" + month + "_bookings.pdf"; // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
