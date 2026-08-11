package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cr-java-0065 and cr-java-0067 fixes applied.
 *
 * cr-java-0065 fix:
 *  All HTTP session state has been migrated from the in-process javax.servlet.http.HttpSession
 *  to Spring Session backed by Amazon ElastiCache for Redis.
 *
 * cr-java-0067 fix:
 *  The unbounded in-memory HashMap cache (bookingCache) has been replaced with
 *  Amazon ElastiCache for Redis via Spring's RedisTemplate.  Every cache entry is
 *  written with an explicit TTL (default 30 minutes, configurable via
 *  BOOKING_CACHE_TTL_MINUTES environment variable) so that:
 *    - Memory growth is bounded and controlled.
 *    - Stale entries are automatically evicted by Redis.
 *    - All application instances share the same cache — no per-instance divergence.
 *    - Cache survives instance restarts and auto-scaling events.
 *
 * How it works:
 *  - spring-session-data-redis + spring-boot-starter-data-redis are on the classpath.
 *  - Spring Boot auto-configuration (@EnableRedisHttpSession via spring.session.store-type=redis)
 *    transparently replaces the servlet container's in-memory session store with Redis.
 *  - RedisTemplate<String, Object> is used for explicit booking cache operations with TTL.
 *  - The SessionRepository<S> bean is injected here to make the Redis session interaction
 *    explicit and testable.
 *
 * Required application.properties keys (see application.properties):
 *   spring.session.store-type=redis
 *   spring.redis.host=${REDIS_HOST:localhost}
 *   spring.redis.port=${REDIS_PORT:6379}
 *   spring.session.redis.flush-mode=on_save
 *   spring.session.redis.namespace=resortslite:session
 *   app.cache.booking.ttl-minutes=${BOOKING_CACHE_TTL_MINUTES:30}
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    /** Redis key prefix for booking cache entries. */
    private static final String BOOKING_CACHE_KEY_PREFIX = "resortslite:booking:";

    @Autowired
    private BookingService bookingService;

    // cr-java-0065 fix: SessionRepository<S> is the Spring Session abstraction that
    // delegates to Amazon ElastiCache for Redis.  All setAttribute / getAttribute calls
    // below operate on the distributed Redis store rather than instance-local memory.
    @Autowired
    private SessionRepository<? extends Session> sessionRepository;

    // cr-java-0067 fix: RedisTemplate replaces the unbounded in-memory HashMap.
    // Each booking entry is stored in Amazon ElastiCache for Redis with an explicit TTL
    // so that memory growth is bounded and cache data is consistent across all instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cr-java-0067 fix: TTL for booking cache entries, sourced from the environment
    // variable BOOKING_CACHE_TTL_MINUTES (default: 30 minutes).  This ensures that
    // stale booking data is automatically evicted from Redis after the configured period.
    @Value("${app.cache.booking.ttl-minutes:${BOOKING_CACHE_TTL_MINUTES:30}}")
    private long bookingCacheTtlMinutes;

    // cr-java-0071 fix: Hard-coded inventory service URL replaced with a value sourced
    // from AWS Systems Manager Parameter Store via Spring's @Value binding.
    // The property 'app.inventory.url' is resolved at startup from the SSM parameter
    // '/resortslite/inventory/url'. This makes the endpoint environment-agnostic:
    // dev, staging, and production each supply their own SSM parameter value without
    // any code change.
    @Value("${app.inventory.url:http://inventory-service.internal:8081/rooms/available}")
    private String inventoryServiceUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 fix: Booking state is now stored in Amazon ElastiCache for Redis
        // via Spring Session's SessionRepository.  A new distributed session is created
        // and its ID is returned to the caller so subsequent requests can retrieve it
        // from any application instance — no server affinity required.
        Session session = sessionRepository.createSession();
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);
        sessionRepository.save(session);

        // cr-java-0067 fix: Booking is cached in Amazon ElastiCache for Redis with an
        // explicit TTL instead of the former unbounded in-memory HashMap.
        // - Key is namespaced to avoid collisions with other applications on the cluster.
        // - TTL is configurable via BOOKING_CACHE_TTL_MINUTES (default 30 minutes).
        // - All application instances share this cache — no per-instance divergence.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        // Expose the Redis-backed session ID so the client can pass it on subsequent calls.
        response.put("sessionId", session.getId());
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false) String sessionId) {

        // cr-java-0065 fix: Guest name is retrieved from the distributed Redis session
        // identified by the sessionId query parameter.  This works correctly regardless
        // of which application instance handles the request, enabling true stateless
        // horizontal scaling behind the AWS ALB.
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            Session session = sessionRepository.findById(sessionId);
            if (session != null) {
                lastGuest = (String) session.getAttribute("guestName");
            }
        }

        // cr-java-0067 fix: Booking details are retrieved from the shared Redis cache.
        // If the entry has expired (TTL elapsed) or was never cached, fall back to the
        // booking service (database lookup) to ensure correctness.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
        @SuppressWarnings("unchecked")
        Map<String, Object> cachedBooking = (Map<String, Object>) redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        result.put("cacheHit", cachedBooking != null);
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 fix (Line 66): Hard-coded URL replaced with the injected field
        // 'inventoryServiceUrl', whose value is sourced from AWS Systems Manager
        // Parameter Store at the key '/resortslite/inventory/url'.
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
