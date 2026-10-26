package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * BookingController — cr-java-0065 FIX + cr-java-0067 FIX:
 *
 * cr-java-0065 FIX:
 *   All HTTP session state is now backed by Amazon ElastiCache for Redis via
 *   Spring Session (spring-session-data-redis). The HttpSession API is preserved
 *   so that no business logic changes are required; Spring Session transparently
 *   serialises every session attribute to Redis on every write and deserialises
 *   it on every read.
 *
 *   Because session data lives in a shared, external Redis cluster rather than
 *   in JVM heap, any EC2 instance (or ECS task) in the Auto Scaling group can
 *   serve any request — there is no server affinity, and session data survives
 *   instance termination and scale-in events.
 *
 * cr-java-0067 FIX:
 *   The unbounded static in-memory HashMap (bookingCache) has been replaced with
 *   Amazon ElastiCache for Redis via Spring Data Redis (RedisTemplate). Each cache
 *   entry is stored under the key "booking:<bookingId>" with a configurable TTL
 *   (default 30 minutes, controlled by BOOKING_CACHE_TTL_MINUTES env var).
 *
 *   Benefits over the old HashMap:
 *     • Controlled expiration — entries are automatically evicted after TTL expires,
 *       preventing unbounded memory growth and stale-data inconsistencies.
 *     • Shared across all instances — every EC2 instance / ECS task reads from and
 *       writes to the same ElastiCache cluster, so cache hits are consistent
 *       regardless of which instance handles the request.
 *     • Survives instance restarts — cached data is not lost when an instance is
 *       terminated by Auto Scaling.
 *
 * Required additions (already applied):
 *   • pom.xml  — spring-session-data-redis + spring-boot-starter-data-redis
 *   • application.properties — spring.session.store-type=redis and
 *     spring.redis.host / spring.redis.port pointing to the ElastiCache
 *     primary endpoint (injected via environment variables at runtime).
 *   • @EnableRedisHttpSession on this class (or on a dedicated @Configuration
 *     class) activates the Spring Session filter that replaces the default
 *     servlet-container session with the Redis-backed implementation.
 */
@RestController
@RequestMapping("/api/bookings")
@EnableRedisHttpSession
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0071 FIX: SsmParameterStoreConfig bean is injected to retrieve environment-specific
    // URLs from AWS Systems Manager Parameter Store instead of hardcoding them in source code.
    @Autowired
    private SsmParameterStoreConfig ssmParameterStoreConfig;

    // cr-java-0067 FIX: Replaced the unbounded static in-memory HashMap with Amazon ElastiCache
    // for Redis via Spring Data Redis (RedisTemplate<String, Object>).
    // Each booking entry is stored with a TTL (default 30 min) so that memory growth is bounded,
    // stale entries are automatically evicted, and the cache is shared across all application
    // instances — eliminating the per-instance inconsistency of the old HashMap approach.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** Cache key prefix for booking entries stored in ElastiCache for Redis. */
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:";

    /**
     * TTL for booking cache entries in ElastiCache for Redis.
     * Controlled by the BOOKING_CACHE_TTL_MINUTES environment variable;
     * defaults to 30 minutes when the variable is not set.
     */
    private static final long BOOKING_CACHE_TTL_MINUTES =
            Long.parseLong(System.getenv().getOrDefault("BOOKING_CACHE_TTL_MINUTES", "30"));

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session attributes are now stored in Amazon ElastiCache for
        // Redis via Spring Session. The HttpSession.setAttribute() calls are unchanged —
        // Spring Session intercepts them and writes the data to the shared Redis cluster,
        // making the state visible to every application instance behind the AWS ALB.
        session.setAttribute("lastBooking", booking); // cr-java-0065 FIXED — Redis-backed
        session.setAttribute("guestName", guestName); // cr-java-0065 FIXED — Redis-backed

        // cr-java-0067 FIX: Store booking in ElastiCache for Redis with a TTL instead of
        // the old unbounded static HashMap. The entry expires automatically after
        // BOOKING_CACHE_TTL_MINUTES minutes, preventing indefinite memory growth and
        // ensuring stale data is not served to other instances in the cluster.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, Duration.ofMinutes(BOOKING_CACHE_TTL_MINUTES));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: Reading from the Redis-backed Spring Session. The value is
        // retrieved from the shared ElastiCache cluster, so it is consistent regardless
        // of which EC2 instance or ECS task handles this request.
        String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065 FIXED — Redis-backed

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX (Line 66): Replaced hard-coded environment-specific URL
        // "http://inventory-service.internal:8081/rooms/available" with a value retrieved
        // from AWS Systems Manager Parameter Store via SsmParameterStoreConfig.
        // The SSM parameter key "/resortslite/inventory/service-url" holds the correct
        // endpoint for each deployment environment (dev, staging, production), enabling
        // environment-agnostic deployments without any code changes between environments.
        String inventoryUrl = ssmParameterStoreConfig.getParameter("/resortslite/inventory/service-url");

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
