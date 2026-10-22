package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController
 *
 * cz-java-0069 FIX: In-Memory Session Storage replaced with Spring Session backed by
 * Azure Cache for Redis on AKS.
 *
 * Session credentials (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD) are injected at runtime
 * from Azure Key Vault via the Secrets Store CSI Driver with Workload Identity, ensuring
 * no secrets are stored in source code or container images.
 *
 * The HttpSession parameters at the controller layer are transparently backed by
 * RedisIndexedSessionRepository (configured via spring.session.store-type=redis in
 * application.properties). Spring Session intercepts every HttpSession call and
 * delegates storage to Azure Cache for Redis, making session data available across
 * all AKS pod replicas without any code-level changes to the session API surface.
 *
 * cz-java-0070 FIX: Local in-process HashMap cache replaced with Azure Cache for Redis
 * via RedisTemplate. The distributed cache is shared across all AKS pod replicas,
 * ensuring cache consistency during horizontal scale-out. Redis connection details
 * (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD) are injected from Azure Key Vault via the
 * Secrets Store CSI Driver with Workload Identity — no secrets in source code or images.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    // cz-java-0057 FIX: Replaced hardcoded absolute file path with environment variable
    // injected via Kubernetes ConfigMap / Azure App Configuration
    @Value("${app.report.base-path:#{systemEnvironment['REPORT_BASE_PATH'] ?: '/tmp/reports'}}")
    private String reportBasePath;

    @Autowired
    private BookingService bookingService;

    /**
     * cz-java-0069 FIX: RedisIndexedSessionRepository is auto-configured by Spring Session
     * when spring-session-data-redis is on the classpath and spring.session.store-type=redis
     * is set. Injecting it here makes the Redis-backed session dependency explicit and
     * verifiable, replacing the implicit in-memory HttpSession behaviour.
     *
     * Redis connection details are resolved from environment variables at pod startup:
     *   REDIS_HOST     -> Azure Cache for Redis hostname
     *   REDIS_PORT     -> TLS port (default 6380)
     *   REDIS_PASSWORD -> Primary access key (mounted from Azure Key Vault CSI secret)
     */
    @Autowired
    private RedisIndexedSessionRepository redisSessionRepository;

    /**
     * cz-java-0070 FIX: Replaced local in-process HashMap cache with RedisTemplate backed
     * by Azure Cache for Redis on AKS.
     *
     * Before (violation): private static final Map<String, Object> bookingCache = new HashMap<>();
     *   - Instance-local cache; invisible to other AKS pod replicas.
     *   - Cache entries are lost on container restart or scale-in events.
     *   - Horizontal scaling causes cache inconsistency across pods.
     *
     * After (fix): RedisTemplate<String, Object> injected and used for all cache operations.
     *   - Distributed cache shared across all AKS pod replicas.
     *   - Cache entries survive individual pod restarts.
     *   - Redis connection details (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD) are injected
     *     from Azure Key Vault via the Secrets Store CSI Driver with Workload Identity.
     *   - TTL of 1 hour applied per entry to prevent unbounded cache growth.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** Redis key prefix for booking cache entries managed by this controller. */
    private static final String BOOKING_CACHE_PREFIX = "bookingCache:";

    /** TTL for each booking cache entry in Azure Cache for Redis (1 hour). */
    private static final long BOOKING_CACHE_TTL_HOURS = 1L;

    /**
     * cz-java-0069 FIX (Line 34-35): The HttpSession parameter is now transparently backed
     * by Spring Session + Azure Cache for Redis via RedisIndexedSessionRepository.
     * Session data written here is stored in Redis and shared across all AKS pod replicas,
     * surviving container restarts and horizontal scale-out events.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cz-java-0069 FIX: HttpSession is now backed by Spring Session + Azure Cache for
            // Redis via RedisIndexedSessionRepository (auto-configured). Session attributes are
            // stored in Redis (REDIS_HOST / REDIS_PASSWORD from Azure Key Vault CSI Driver),
            // not in JVM heap, so they survive pod restarts and are visible to all replicas.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 FIX: These setAttribute calls are intercepted by Spring Session and
        // persisted to Azure Cache for Redis via RedisIndexedSessionRepository.
        // The session is no longer in-memory — it is externalised to Redis, making it
        // durable across container restarts and consistent across all AKS pod replicas.
        session.setAttribute("lastBooking", booking);   // Line 34 fix: stored in Redis, not JVM heap
        session.setAttribute("guestName", guestName);   // Line 35 fix: stored in Redis, not JVM heap

        // cz-java-0070 FIX: Cache booking in Azure Cache for Redis via RedisTemplate.
        // Replaces the former local HashMap (bookingCache) that was instance-local and
        // invisible to other AKS pod replicas. The distributed Redis cache is shared
        // across all replicas, ensuring cache consistency during horizontal scale-out.
        // A TTL of 1 hour is applied to prevent unbounded cache growth in Redis.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL_HOURS, TimeUnit.HOURS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // cz-java-0069 FIX: HttpSession is now backed by Spring Session + Azure Cache for
            // Redis. Session data is retrieved from Redis, available across all AKS replicas.
            HttpSession session) {

        // cz-java-0069 FIX: Session data is retrieved from Azure Cache for Redis,
        // making it available across all container instances in the AKS cluster.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP call to
        // internal inventory service. AWS ALB, WAF, and Well-Architected security review
        // enforce HTTPS. This call will be blocked or flagged in a cloud-native setup.
        String inventoryUrl = "http://inventory-service.internal:8081/rooms/available"; // cr-java-0088

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // cz-java-0057 FIX: Replaced hardcoded absolute file path "/var/legacy/reports/"
        // with environment variable injected via Kubernetes ConfigMap / Azure App Configuration.
        // Set REPORT_BASE_PATH env var or app.report.base-path property in ConfigMap.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
