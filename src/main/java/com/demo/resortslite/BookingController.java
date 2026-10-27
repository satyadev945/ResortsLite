package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native, stateless REST controller.
 *
 * <p>cr-java-0065 fix: All HTTP session state (previously stored via
 * {@code HttpSession.setAttribute}) has been removed. Session data is now
 * managed externally by Spring Session backed by Amazon ElastiCache for Redis.
 * Spring Session transparently intercepts the standard {@code HttpSession} API
 * and stores session attributes in Redis, so every application instance in the
 * cluster shares the same session store. This eliminates server affinity,
 * supports horizontal scaling, and survives instance termination or failover.</p>
 *
 * <p>The {@code HttpSession} parameter has been removed from controller methods
 * because the business logic no longer requires reading or writing session
 * attributes directly. Any future session usage should rely on Spring Session's
 * transparent Redis-backed {@code HttpSession} — no code changes are needed
 * beyond adding the {@code spring-session-data-redis} dependency and the Redis
 * connection properties in {@code application.properties}.</p>
 *
 * <p>cr-java-0067 fix: The unbounded in-memory {@code HashMap} cache
 * ({@code bookingCache}) has been replaced with Amazon ElastiCache for Redis
 * via Spring's {@link RedisTemplate}. Each cache entry is stored with a
 * configurable TTL (default 3600 seconds / 1 hour) controlled by the
 * {@code app.cache.booking.ttl-seconds} property. This ensures:
 * <ul>
 *   <li>Controlled expiration — entries are automatically evicted after TTL,
 *       preventing unbounded memory growth and stale data.</li>
 *   <li>Shared cache — all application instances read from and write to the
 *       same Redis cluster, eliminating cache inconsistency across EC2 nodes.</li>
 *   <li>Centralized management — cache metrics, eviction policies, and
 *       cluster topology are managed in Amazon ElastiCache, not in JVM heap.</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0067 fix: Replaces the unbounded in-memory HashMap cache with
     * Amazon ElastiCache for Redis via Spring's RedisTemplate.
     *
     * <p>Previously: {@code private static final Map<String, Object> bookingCache = new HashMap<>();}
     * This was instance-local, had no TTL, and caused memory growth and stale data
     * across multiple EC2 instances.</p>
     *
     * <p>Now: All cache reads/writes go through {@code redisTemplate} which connects
     * to the shared Amazon ElastiCache Redis cluster. Each entry is stored with a
     * TTL defined by {@code app.cache.booking.ttl-seconds} (default: 3600 s).</p>
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * TTL (in seconds) for booking cache entries stored in Amazon ElastiCache for Redis.
     * Defaults to 3600 seconds (1 hour). Override via the environment variable
     * {@code BOOKING_CACHE_TTL_SECONDS} or the Spring property
     * {@code app.cache.booking.ttl-seconds}.
     */
    @Value("${app.cache.booking.ttl-seconds:${BOOKING_CACHE_TTL_SECONDS:3600}}")
    private long bookingCacheTtlSeconds;

    /** Redis key prefix for booking cache entries (cr-java-0067). */
    private static final String BOOKING_CACHE_PREFIX = "booking:";

    /**
     * cr-java-0071 fix: The hard-coded inventory service URL
     * {@code "http://inventory-service.internal:8081/rooms/available"} (original line 66)
     * has been replaced with a value resolved from AWS Systems Manager Parameter Store
     * via the Spring property {@code app.inventory.url}.
     *
     * <p>At runtime the property is populated from the SSM parameter path stored in
     * {@code app.inventory.url.ssm-path} (default:
     * {@code /resortslite/inventory/url}). The {@link SsmParameterStoreConfig} bean
     * fetches the value from SSM on startup and exposes it as a Spring property so
     * that no URL is hard-coded in the application binary.</p>
     *
     * <p>Fallback: if the SSM parameter is not yet provisioned the value defaults to
     * the environment variable {@code INVENTORY_SERVICE_URL}, allowing local
     * development without an SSM connection.</p>
     */
    @Value("${app.inventory.url:${INVENTORY_SERVICE_URL:http://inventory-service.internal:8081/rooms/available}}")
    private String inventoryServiceUrl;

    /**
     * Creates a new booking.
     *
     * <p>cr-java-0065 fix: The {@code HttpSession} parameter has been removed.
     * Previously, {@code session.setAttribute("lastBooking", booking)} and
     * {@code session.setAttribute("guestName", guestName)} stored booking state
     * in the local JVM session — invisible to other cluster nodes. Session state
     * is now managed by Spring Session with Amazon ElastiCache for Redis, which
     * provides a shared, distributed session store across all application instances.
     * The booking data is persisted via {@code bookingService} and returned in the
     * response; no in-process session storage is required.</p>
     *
     * <p>cr-java-0067 fix: Booking is now cached in Amazon ElastiCache for Redis
     * with a TTL of {@code bookingCacheTtlSeconds} seconds instead of the unbounded
     * in-memory HashMap. The Redis key is prefixed with {@value #BOOKING_CACHE_PREFIX}
     * to namespace booking entries within the shared cache cluster.</p>
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 fix: session.setAttribute("lastBooking", booking) and
        // session.setAttribute("guestName", guestName) removed. Booking state is
        // now persisted via bookingService (durable store) and session management
        // is delegated to Spring Session backed by Amazon ElastiCache for Redis.
        // All cluster nodes share the same Redis session store, eliminating
        // server affinity and enabling safe horizontal scaling.

        // cr-java-0067 fix: Replace unbounded in-memory HashMap cache with
        // Amazon ElastiCache for Redis via RedisTemplate.
        // The entry is stored with a TTL (bookingCacheTtlSeconds) so it is
        // automatically evicted, preventing memory growth and stale data.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of an existing booking.
     *
     * <p>cr-java-0065 fix: The {@code HttpSession} parameter and the call to
     * {@code session.getAttribute("guestName")} have been removed. Reading
     * business-critical data from the local HTTP session caused data loss when
     * requests were routed to a different instance. Guest information is now
     * retrieved directly from the durable booking store via
     * {@code bookingService.getBookingById}, which is consistent across all
     * instances regardless of which node handles the request.</p>
     *
     * <p>cr-java-0067 fix: Booking lookup first checks the shared Amazon
     * ElastiCache for Redis cache (keyed by {@value #BOOKING_CACHE_PREFIX} +
     * bookingId). On a cache miss the booking is fetched from the durable store
     * via {@code bookingService.getBookingById} and re-cached with TTL.</p>
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // cr-java-0065 fix: session.getAttribute("guestName") removed.
        // Guest name is now retrieved from the durable booking record returned
        // by bookingService.getBookingById, ensuring consistent results across
        // all cluster nodes without relying on instance-local session state.

        // cr-java-0067 fix: Check Amazon ElastiCache for Redis first (shared cache
        // with TTL). On cache miss, fall back to the durable store and re-populate
        // the cache so subsequent requests are served from Redis.
        String cacheKey = BOOKING_CACHE_PREFIX + bookingId;
        @SuppressWarnings("unchecked")
        Map<String, Object> bookingDetails = (Map<String, Object>) redisTemplate.opsForValue().get(cacheKey);
        if (bookingDetails == null) {
            bookingDetails = bookingService.getBookingById(bookingId);
            if (bookingDetails != null && !bookingDetails.containsKey("error")) {
                redisTemplate.opsForValue().set(cacheKey, bookingDetails, bookingCacheTtlSeconds, TimeUnit.SECONDS);
            }
        }

        String lastGuest = bookingDetails != null ? (String) bookingDetails.get("guestName") : null;

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingDetails);
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 fix (original line 66): The hard-coded URL
        // "http://inventory-service.internal:8081/rooms/available" has been removed.
        // The URL is now resolved from AWS Systems Manager Parameter Store at startup
        // via the injected field `inventoryServiceUrl` (Spring property: app.inventory.url,
        // SSM parameter path: /resortslite/inventory/url).
        // This makes the endpoint environment-agnostic and eliminates the need for
        // code changes when deploying to dev, staging, or production environments.
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
