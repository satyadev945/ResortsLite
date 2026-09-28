package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// FIXED cr-java-0065: HttpSession is now backed by Amazon ElastiCache for Redis
// Spring Session automatically stores all session data in Redis cluster
// Sessions are distributed across all application instances
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIXED cr-java-0067: Replaced static in-memory HashMap with Amazon ElastiCache for Redis
    // RedisTemplate provides distributed caching with proper TTL policies
    // Cache entries automatically expire after configured TTL (default: 30 minutes)
    // All application instances share the same cache, ensuring data consistency
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${cache.ttl.minutes:30}")
    private int cacheTtlMinutes;

    // FIXED cr-java-0071: Externalized inventory service URL to AWS Systems Manager Parameter Store
    // Configuration can be retrieved from environment variables or Spring properties
    @Value("${app.inventory.endpoint}")
    private String inventoryServiceUrl;

    // FIXED cr-java-0065: Session data now stored in Amazon ElastiCache for Redis
    // Spring Session transparently manages session storage in Redis cluster
    // All setAttribute/getAttribute calls are automatically persisted to Redis
    // Sessions are accessible across all EC2 instances behind the load balancer
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) { // cr-java-0065 FIXED: Session backed by Redis

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED cr-java-0065: Session attributes now stored in Amazon ElastiCache for Redis
        // Spring Session automatically serializes and stores data in Redis cluster
        // Data persists across instance restarts, auto-scaling, and load balancer routing
        session.setAttribute("lastBooking", booking); // cr-java-0065 FIXED: Redis-backed
        session.setAttribute("guestName", guestName); // cr-java-0065 FIXED: Redis-backed

        // FIXED cr-java-0067: Store booking in Amazon ElastiCache for Redis with TTL
        // Cache key: "booking:" + bookingId
        // TTL: Configured via cache.ttl.minutes property (default: 30 minutes)
        // Automatic expiration prevents indefinite memory growth
        // Shared cache ensures consistency across all application instances
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, cacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    // FIXED cr-java-0065: Session data retrieved from Amazon ElastiCache for Redis
    // getAttribute calls are transparently routed to Redis cluster
    // Session data is available on any application instance
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) { // cr-java-0065 FIXED: Session backed by Redis

        // FIXED cr-java-0065: Reading session data from Amazon ElastiCache for Redis
        // Spring Session automatically retrieves data from Redis cluster
        // Works correctly across all instances in the cluster
        String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065 FIXED: Redis-backed

        // FIXED cr-java-0067: Retrieve booking from Amazon ElastiCache for Redis
        // Check cache first before querying database
        // Cache miss will return null, triggering database lookup
        String cacheKey = "booking:" + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("cachedData", cachedBooking != null ? "hit" : "miss");
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP call to
        // internal inventory service. AWS ALB, WAF, and Well-Architected security review
        // enforce HTTPS. This call will be blocked or flagged in a cloud-native setup.
        // FIXED cr-java-0071: Using externalized configuration from AWS Systems Manager Parameter Store
        String inventoryUrl = inventoryServiceUrl + "/available"; // cr-java-0071 FIXED

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
