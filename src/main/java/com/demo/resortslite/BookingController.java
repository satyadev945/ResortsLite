package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import com.demo.resortslite.config.AwsParameterStoreConfig;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * Booking Controller with Distributed Session Management and Redis Caching
 * 
 * FIXED cr-java-0065 [Cloud Compatibility / Mandatory]: HTTP Session State Storage
 * 
 * Session data is now stored in Amazon ElastiCache for Redis via Spring Session Data Redis.
 * The HttpSession object is transparently backed by Redis, enabling:
 * - Stateless application instances that can scale horizontally across multiple EC2 instances
 * - Session persistence across instance restarts, deployments, and auto-scaling events
 * - Session sharing across all instances behind AWS Application Load Balancer
 * - High availability through ElastiCache replication and automatic failover
 * 
 * Implementation:
 * - HttpSession API remains unchanged (no code refactoring required)
 * - Spring Session intercepts all session operations and stores data in Redis
 * - Session data is serialized and stored with configurable TTL (30 minutes default)
 * - Redis connection configured via RedisSessionConfig and application.properties
 * 
 * FIXED cr-java-0067 [Cloud Compatibility / Mandatory]: In-Memory Caching Without TTL
 * 
 * Replaced unbounded in-memory HashMap cache with Amazon ElastiCache for Redis with TTL.
 * Benefits:
 * - Controlled cache expiration (30-minute TTL) prevents indefinite memory growth
 * - Consistent cache data across all application instances in the cluster
 * - Centralized cache management via ElastiCache
 * - Automatic eviction of stale data
 * - No risk of out-of-memory errors from unbounded cache growth
 * 
 * Implementation:
 * - Removed static HashMap bookingCache (line 19)
 * - Added Spring Cache annotations (@Cacheable, @CachePut, @CacheEvict)
 * - Cache operations automatically use Redis via RedisCacheConfig
 * - Cache entries expire after 30 minutes (configurable via application.properties)
 * 
 * AWS Deployment:
 * 1. Create ElastiCache Redis cluster in same VPC as application
 * 2. Configure security groups to allow traffic on port 6379
 * 3. Set environment variable: SPRING_REDIS_HOST=<elasticache-endpoint>
 * 4. Deploy application - sessions and cache will automatically use Redis
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private AwsParameterStoreConfig parameterStoreConfig;

    // FIXED cr-java-0067: Removed static in-memory cache without TTL
    // Replaced with Redis-backed cache with 30-minute TTL via @Cacheable annotations
    // Old code: private static final Map<String, Object> bookingCache = new HashMap<>();

    /**
     * Create a new booking and store in distributed session and cache
     * 
     * FIXED cr-java-0065: Session attributes are now stored in Amazon ElastiCache for Redis
     * instead of local memory. Session data is accessible from any application instance.
     * 
     * FIXED cr-java-0067: Booking data is now cached in Redis with 30-minute TTL using
     * @CachePut annotation. Cache is shared across all instances and automatically expires.
     */
    @PostMapping("/create")
    @CachePut(value = "bookingCache", key = "#result['bookingId']")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED cr-java-0065: Session data now stored in Redis via Spring Session
        // These attributes are persisted to ElastiCache and accessible from any instance
        session.setAttribute("lastBooking", booking); // Stored in Redis
        session.setAttribute("guestName", guestName); // Stored in Redis

        // FIXED cr-java-0067: Removed manual cache put operation
        // @CachePut annotation automatically stores booking in Redis cache with TTL
        // Old code: bookingCache.put((String) booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Get booking status with session data from distributed store and Redis cache
     * 
     * FIXED cr-java-0065: Session attributes are retrieved from Amazon ElastiCache for Redis.
     * Works correctly regardless of which EC2 instance handles the request.
     * 
     * FIXED cr-java-0067: Booking details are retrieved from Redis cache using @Cacheable.
     * If cache entry exists and hasn't expired (30-minute TTL), returns cached data.
     * If cache miss or expired, fetches from database and caches result.
     */
    @GetMapping("/status/{bookingId}")
    @Cacheable(value = "bookingCache", key = "#bookingId")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // FIXED cr-java-0065: Reading from Redis-backed session
        // Session data is available across all instances in the cluster
        String lastGuest = (String) session.getAttribute("guestName"); // Retrieved from Redis

        // FIXED cr-java-0067: @Cacheable annotation automatically checks Redis cache first
        // If cache hit, returns cached data without calling bookingService
        // If cache miss, calls bookingService and stores result in Redis with 30-minute TTL
        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Delete booking and evict from Redis cache
     * 
     * FIXED cr-java-0067: @CacheEvict annotation automatically removes booking from Redis cache
     * when booking is deleted, ensuring cache consistency across all instances.
     */
    @DeleteMapping("/cancel/{bookingId}")
    @CacheEvict(value = "bookingCache", key = "#bookingId")
    public Map<String, Object> cancelBooking(@PathVariable String bookingId) {
        // FIXED cr-java-0067: Cache entry automatically removed from Redis by @CacheEvict
        // No manual cache management required
        Map<String, Object> response = new HashMap<>();
        response.put("status", "cancelled");
        response.put("bookingId", bookingId);
        response.put("message", "Booking cancelled and removed from cache");
        return response;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIXED cr-java-0071 [Cloud Compatibility / Mandatory]: Hard-coded environment URL replaced
        // with AWS Systems Manager Parameter Store configuration. URL is now externalized and
        // environment-agnostic, enabling seamless deployment across dev, staging, and production.
        // Parameter Store path: /resortslite/inventory/service/url
        String inventoryUrl = parameterStoreConfig.getInventoryServiceUrl(); // FIXED cr-java-0071

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
