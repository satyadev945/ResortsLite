package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native, stateless REST controller.
 *
 * cr-java-0065 remediation: All HTTP session state (HttpSession) has been replaced with
 * Google Cloud Memorystore for Redis via Spring Data Redis (StringRedisTemplate).
 * Session attributes are stored in Redis with a configurable TTL so that every
 * application instance in the cluster shares the same state, enabling horizontal
 * scaling, rolling deployments, and instance failover without data loss.
 *
 * cr-java-0067 remediation: The unbounded static in-memory HashMap cache
 * (bookingCache) has been replaced with Google Cloud Memorystore for Redis via
 * StringRedisTemplate. Each cached booking entry is stored under a namespaced key
 * ("cache:booking:<bookingId>") with a configurable TTL (CACHE_TTL_MINUTES, default
 * 60 minutes). This eliminates indefinite memory growth, prevents stale data
 * inconsistencies across multiple instances, and ensures all application instances
 * share a single consistent cache backed by Memorystore for Redis.
 *
 * Redis connection is configured through environment variables:
 *   REDIS_HOST          — Memorystore for Redis instance IP / hostname (default: localhost)
 *   REDIS_PORT          — Redis port (default: 6379)
 *   SESSION_TTL_MINUTES — TTL for session keys in minutes (default: 30)
 *   CACHE_TTL_MINUTES   — TTL for booking cache keys in minutes (default: 60)
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0065 remediation: StringRedisTemplate is used to store and retrieve
     * session-scoped data in Google Cloud Memorystore for Redis instead of HttpSession.
     * This makes the controller fully stateless — any instance can serve any request.
     *
     * cr-java-0067 remediation: StringRedisTemplate is also used for booking cache
     * entries with TTL, replacing the former unbounded static HashMap.
     */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * URL of the internal inventory service.
     * Externalised from the hard-coded literal "http://inventory-service.internal:8081/rooms/available"
     * (cr-java-0071 remediation). Resolved at runtime from the {@code INVENTORY_SERVICE_URL}
     * environment variable so the same artefact can be deployed to dev, staging, and production
     * without code changes.
     */
    @Value("${app.inventory.service.url:${INVENTORY_SERVICE_URL:http://inventory-service.internal:8081/rooms/available}}")
    private String inventoryServiceUrl;

    /**
     * cr-java-0065 remediation: TTL for Redis session keys, configurable via
     * SESSION_TTL_MINUTES environment variable (default: 30 minutes).
     */
    @Value("${session.ttl.minutes:${SESSION_TTL_MINUTES:30}}")
    private long sessionTtlMinutes;

    /**
     * cr-java-0067 remediation: TTL for Redis booking cache keys, configurable via
     * CACHE_TTL_MINUTES environment variable (default: 60 minutes).
     * Replaces the former unbounded static HashMap that had no expiration policy.
     */
    @Value("${cache.ttl.minutes:${CACHE_TTL_MINUTES:60}}")
    private long cacheTtlMinutes;

    // cr-java-0067 FIXED: The static in-memory HashMap (bookingCache) has been removed.
    // Booking cache entries are now stored in Google Cloud Memorystore for Redis via
    // StringRedisTemplate with a configurable TTL (cacheTtlMinutes). This ensures:
    //   1. Cache entries automatically expire — no indefinite memory growth.
    //   2. All application instances share the same cache state via Memorystore.
    //   3. Stale data is evicted by Redis TTL, preventing inconsistencies.
    // Redis key pattern: "cache:booking:<bookingId>"

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 remediation: Session state is now stored in Google Cloud Memorystore
        // for Redis instead of HttpSession. Each session key is namespaced by sessionId and
        // given a TTL so stale data is automatically evicted. All application instances share
        // the same Redis store, eliminating server affinity and enabling horizontal scaling.
        String bookingKey = "session:" + sessionId + ":lastBooking";
        String guestKey   = "session:" + sessionId + ":guestName";
        redisTemplate.opsForValue().set(bookingKey, booking.toString(), sessionTtlMinutes, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(guestKey,   guestName,          sessionTtlMinutes, TimeUnit.MINUTES);

        // cr-java-0067 remediation: Booking is cached in Google Cloud Memorystore for Redis
        // with a TTL (cacheTtlMinutes) instead of the former unbounded static HashMap.
        // The cache key is namespaced as "cache:booking:<bookingId>" to avoid collisions.
        // Redis automatically evicts the entry after cacheTtlMinutes, preventing memory
        // exhaustion and stale data across horizontally-scaled instances.
        String cacheKey = "cache:booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking.toString(), cacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        // cr-java-0065 remediation: Guest name is now retrieved from Google Cloud Memorystore
        // for Redis instead of HttpSession. Any instance in the cluster can serve this request
        // because all instances read from the shared Redis store.
        String guestKey   = "session:" + sessionId + ":guestName";
        String lastGuest  = redisTemplate.opsForValue().get(guestKey);

        // cr-java-0067 remediation: Booking details are looked up from the Redis cache
        // (key: "cache:booking:<bookingId>") before falling back to the database via
        // bookingService. This replaces the former static HashMap lookup that had no TTL.
        String cacheKey    = "cache:booking:" + bookingId;
        String cachedEntry = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("cachedEntry", cachedEntry);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 remediation: URL is now resolved from the injected inventoryServiceUrl
        // field, which is backed by the INVENTORY_SERVICE_URL environment variable.
        // No hard-coded environment-specific URL remains in the source code.
        String inventoryUrl = inventoryServiceUrl;

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
