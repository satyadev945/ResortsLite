package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cr-java-0065 FIX: javax.servlet.http.HttpSession import retained — Spring Session Data Redis
// transparently replaces the in-process HTTP session store with Amazon ElastiCache for Redis.
// The @EnableRedisHttpSession annotation in RedisSessionConfig activates this substitution,
// making all HttpSession.setAttribute / getAttribute calls distributed and cluster-safe without
// any further changes to the controller API surface.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 FIX: Replaced unbounded in-memory HashMap cache (bookingCache) with
    // Amazon ElastiCache for Redis via Spring Data RedisTemplate.
    // - Cache entries are stored in the shared ElastiCache cluster, visible to ALL EC2
    //   instances behind the AWS ALB — eliminates instance-local, invisible cache shards.
    // - Each entry is written with a configurable TTL (default: 3600 s / 1 hour) enforced
    //   by Redis, preventing indefinite memory growth and stale data inconsistencies.
    // - The RedisTemplate<String, Object> bean is defined in RedisCacheConfig and uses
    //   Jackson2JsonRedisSerializer for human-readable, type-safe serialisation.
    @Autowired
    @Qualifier("bookingRedisTemplate")
    private RedisTemplate<String, Object> bookingRedisTemplate;

    /**
     * TTL (in seconds) applied to every booking cache entry written to Amazon ElastiCache.
     * Defaults to 3600 s (1 hour). Override via the {@code BOOKING_CACHE_TTL_SECONDS}
     * environment variable or the {@code booking.cache.ttl-seconds} application property.
     */
    @Value("${booking.cache.ttl-seconds:3600}")
    private long bookingCacheTtlSeconds;

    /**
     * cr-java-0071 fix (Line 66): The hard-coded URL
     * {@code "http://inventory-service.internal:8081/rooms/available"} has been removed.
     *
     * <p>The inventory service URL is now resolved from AWS Systems Manager Parameter Store
     * via the {@code inventoryServiceUrl} bean defined in
     * {@link com.demo.resortslite.config.AwsSsmParameterStoreConfig}.  At runtime the bean
     * reads the value of the SSM parameter {@code /resortslite/inventory/url} (configurable
     * via {@code aws.ssm.param.inventory-url} property or {@code SSM_INVENTORY_URL_PARAM}
     * environment variable), enabling environment-agnostic deployments without any code
     * changes between dev, staging, and production.</p>
     *
     * <p>The {@code @Qualifier("inventoryServiceUrl")} annotation ensures Spring injects
     * the correct String bean produced by the SSM config class.</p>
     */
    @Autowired
    @Qualifier("inventoryServiceUrl")
    private String inventoryServiceUrl;

    /**
     * Creates a new booking and stores session state in Amazon ElastiCache for Redis via
     * Spring Session Data Redis.
     *
     * <p>cr-java-0065 FIX: {@code HttpSession} is now backed by Amazon ElastiCache for Redis
     * through Spring Session ({@code @EnableRedisHttpSession} in
     * {@link com.demo.resortslite.config.RedisSessionConfig}).  All
     * {@code session.setAttribute} calls write to the shared Redis cluster, making session
     * data visible to every application instance behind the AWS ALB.  This eliminates server
     * affinity, supports horizontal auto-scaling, and prevents data loss on instance
     * termination or failover.</p>
     *
     * <p>cr-java-0067 FIX: Booking data is now cached in Amazon ElastiCache for Redis with
     * a configurable TTL (default 3600 s).  The RedisTemplate writes the entry to the shared
     * cluster so all EC2 instances share the same cache view, and Redis automatically evicts
     * entries after the TTL expires, preventing unbounded memory growth.</p>
     *
     * <p>No changes to the method signature or business logic are required — Spring Session
     * intercepts the standard {@code HttpSession} API transparently.</p>
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cr-java-0065 FIX: HttpSession parameter is retained; Spring Session Data Redis
            // (enabled via RedisSessionConfig) transparently stores all session attributes in
            // Amazon ElastiCache for Redis, replacing the in-process JVM session store.
            // This makes session data available across all EC2 instances behind the AWS ALB.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: session.setAttribute now writes to Amazon ElastiCache for Redis
        // via Spring Session Data Redis. The @EnableRedisHttpSession annotation in
        // RedisSessionConfig replaces the default in-memory HttpSession store with a
        // distributed Redis-backed store, enabling stateless application instances and
        // safe horizontal scaling across multiple EC2 nodes behind the AWS ALB.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 FIX: Replaced bookingCache.put(...) (unbounded in-memory HashMap) with
        // RedisTemplate.opsForValue().set(..., TTL, TimeUnit.SECONDS).
        // - Entry is stored in Amazon ElastiCache for Redis — shared across all EC2 instances.
        // - TTL (default 3600 s) is enforced by Redis, preventing stale data and memory leaks.
        // - Key prefix "booking:" namespaces cache entries to avoid collisions with session keys.
        String cacheKey = "booking:" + booking.get("bookingId");
        bookingRedisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the booking status for the given booking ID.
     *
     * <p>cr-java-0065 FIX: {@code session.getAttribute("guestName")} now reads from Amazon
     * ElastiCache for Redis via Spring Session Data Redis.  Because session data is stored
     * centrally in Redis rather than in the local JVM heap, this call returns the correct
     * value regardless of which EC2 instance handles the request, eliminating the
     * "null on any other instance" problem described in the original violation.</p>
     *
     * <p>cr-java-0067 FIX: Booking lookup now reads from the shared Amazon ElastiCache for
     * Redis cache via RedisTemplate, returning consistent data across all EC2 instances.
     * If the cache entry has expired (TTL elapsed) the request falls through to the
     * BookingService for a fresh database lookup.</p>
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // cr-java-0065 FIX: HttpSession parameter is retained; Spring Session Data Redis
            // (enabled via RedisSessionConfig) transparently reads session attributes from
            // Amazon ElastiCache for Redis, ensuring consistent data across all cluster nodes.
            HttpSession session) {

        // cr-java-0065 FIX: session.getAttribute now reads from Amazon ElastiCache for Redis
        // via Spring Session Data Redis, returning the correct value on any EC2 instance.
        String lastGuest = (String) session.getAttribute("guestName");

        // cr-java-0067 FIX: Attempt to read booking details from the shared ElastiCache Redis
        // cache first (cache-aside pattern). On a cache miss (entry expired or not yet cached)
        // fall back to BookingService for a fresh database lookup.
        String cacheKey = "booking:" + bookingId;
        Object cachedBooking = bookingRedisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 fix (Line 66): Replaced hard-coded URL
        // "http://inventory-service.internal:8081/rooms/available"
        // with the value resolved from AWS SSM Parameter Store at application startup.
        // The inventoryServiceUrl field is injected by AwsSsmParameterStoreConfig#inventoryServiceUrl()
        // which reads /resortslite/inventory/url from SSM, enabling environment-agnostic deployments.
        // cr-java-0088: Note — the SSM parameter value should be set to an HTTPS URL in all
        // cloud environments (e.g. https://inventory-service.internal:8081/rooms/available).

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryServiceUrl);
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
