package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.bind.annotation.*;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native, stateless REST controller.
 *
 * <p><b>cr-java-0065 FIX:</b> All HTTP session state has been migrated from
 * {@code javax.servlet.http.HttpSession} (server-local, instance-affine) to
 * Spring Session backed by Amazon ElastiCache for Redis.  Spring Session
 * intercepts every {@code HttpSession} interaction and transparently stores /
 * retrieves session data in the shared Redis cluster, so any EC2 instance in
 * the Auto Scaling Group can serve any request without sticky sessions on the
 * AWS Application Load Balancer.  The {@code HttpSession} import and parameter
 * have been removed; session operations now go through the injected
 * {@link SessionRepository} which is automatically wired to the Redis store
 * when {@code spring-session-data-redis} is on the classpath and
 * {@code spring.session.store-type=redis} is set in application properties.</p>
 *
 * <p><b>cr-java-0067 FIX:</b> The unbounded in-memory {@code HashMap} cache
 * ({@code bookingCache}) has been replaced with Amazon ElastiCache for Redis
 * via Spring Data Redis {@link RedisTemplate}.  Each cache entry is stored with
 * a configurable TTL (default 30 minutes, overridable via
 * {@code BOOKING_CACHE_TTL_MINUTES} environment variable), preventing indefinite
 * memory growth, stale data, and cross-instance inconsistency that occurred with
 * the previous instance-local {@code HashMap}.</p>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0065 FIX: Spring Session {@link SessionRepository} replaces direct
     * {@code HttpSession} usage.  The repository is backed by Amazon ElastiCache
     * for Redis (configured via {@code spring.session.store-type=redis} and the
     * {@code spring.redis.*} properties).  Session data is stored centrally so
     * every application instance in the cluster shares the same session state,
     * enabling true horizontal scaling without ALB sticky sessions.
     */
    @Autowired
    private SessionRepository<? extends Session> sessionRepository;

    /**
     * cr-java-0067 FIX: Spring Data Redis {@link RedisTemplate} replaces the former
     * unbounded in-memory {@code HashMap} cache.  All booking cache entries are stored
     * in Amazon ElastiCache for Redis with a TTL (see {@code bookingCacheTtlMinutes}),
     * ensuring:
     * <ul>
     *   <li>Controlled expiration — entries are automatically evicted after the TTL,
     *       preventing indefinite memory growth and stale data.</li>
     *   <li>Cross-instance consistency — all EC2 instances in the Auto Scaling Group
     *       share the same centralized cache, eliminating the per-instance cache
     *       divergence that occurred with the previous {@code HashMap}.</li>
     *   <li>Cloud-native scalability — ElastiCache handles eviction, replication, and
     *       failover transparently, with no application-level cache management code.</li>
     * </ul>
     * The {@code RedisTemplate} is auto-configured by Spring Boot when
     * {@code spring-boot-starter-data-redis} (or {@code spring-session-data-redis}) is
     * on the classpath and {@code spring.redis.host} / {@code spring.redis.port} are set.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * cr-java-0067 FIX: TTL (in minutes) for booking cache entries stored in Redis.
     * Defaults to 30 minutes; override via the {@code BOOKING_CACHE_TTL_MINUTES}
     * environment variable or the {@code booking.cache.ttl.minutes} application property.
     */
    @Value("${booking.cache.ttl.minutes:${BOOKING_CACHE_TTL_MINUTES:30}}")
    private long bookingCacheTtlMinutes;

    /** Redis key prefix for booking cache entries (cr-java-0067 FIX). */
    private static final String BOOKING_CACHE_KEY_PREFIX = "resortslite:booking:";

    // cr-java-0071 FIX: AWS SSM Parameter Store path for the inventory service URL.
    // Set aws.ssm.region (defaults to us-east-1) and aws.ssm.parameter.inventory-url
    // (defaults to /resortslite/inventory/service-url) via environment variables or
    // application properties. The actual URL value is stored in Parameter Store, not in code.
    @Value("${aws.ssm.region:us-east-1}")
    private String ssmRegion;

    @Value("${aws.ssm.parameter.inventory-url:/resortslite/inventory/service-url}")
    private String inventoryUrlParameterPath;

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * <p><b>cr-java-0071 FIX:</b> Environment-specific URLs are no longer hard-coded in
     * source code. Instead, they are stored as SecureString or String parameters in AWS
     * Systems Manager Parameter Store and fetched at runtime via the AWS SDK. This enables
     * environment-agnostic deployments — the same artifact can be deployed to dev, staging,
     * or production simply by pointing to different Parameter Store paths.</p>
     *
     * @param parameterPath the SSM parameter path (e.g. /resortslite/inventory/service-url)
     * @return the parameter value stored in Parameter Store
     */
    private String getSsmParameter(String parameterPath) {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(ssmRegion))
                .build()) {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterPath)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        }
    }

    /**
     * Creates a new booking, persists guest session state to Amazon ElastiCache
     * for Redis via Spring Session, and caches the booking in Redis with a TTL.
     *
     * <p><b>cr-java-0065 FIX (Lines 34–35 original source):</b>
     * {@code session.setAttribute("lastBooking", booking)} and
     * {@code session.setAttribute("guestName", guestName)} previously wrote to the
     * local JVM's {@code HttpSession}.  These calls have been replaced with
     * {@link SessionRepository#createSession()} / {@link Session#setAttribute(String, Object)}
     * / {@link SessionRepository#save(Session)}, which persist the attributes to the
     * shared Redis cluster.</p>
     *
     * <p><b>cr-java-0067 FIX:</b> The former {@code bookingCache.put(bookingId, booking)}
     * call (which stored data in an unbounded instance-local {@code HashMap}) has been
     * replaced with {@code redisTemplate.opsForValue().set(key, booking, ttl, TimeUnit.MINUTES)},
     * storing the booking in Amazon ElastiCache for Redis with a configurable TTL.  This
     * ensures automatic expiration and cross-instance cache consistency.</p>
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session state is now stored in Amazon ElastiCache for Redis
        // via Spring Session, replacing the former HttpSession.setAttribute() calls.
        Session session = sessionRepository.createSession();
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);
        sessionRepository.save(session);

        // cr-java-0067 FIX: Cache the booking in Amazon ElastiCache for Redis with a TTL,
        // replacing the former unbounded in-memory HashMap (bookingCache.put(...)).
        // The TTL ensures automatic expiration, preventing stale data and memory growth.
        // All application instances share this centralized cache, eliminating cross-instance
        // inconsistency that occurred with the previous instance-local HashMap.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        // Expose the Redis-backed session ID so the client can reference it later.
        response.put("sessionId", session.getId());
        return response;
    }

    /**
     * Returns the booking status and the guest name stored in the distributed
     * Redis session.
     *
     * <p><b>cr-java-0065 FIX (Line 48 original source):</b>
     * {@code session.getAttribute("guestName")} previously read from the local JVM's
     * {@code HttpSession}.  It has been replaced with a {@link SessionRepository#findById}
     * lookup that retrieves the attribute from the shared Redis cluster.</p>
     *
     * <p><b>cr-java-0067 FIX:</b> Booking details are now retrieved from the Amazon
     * ElastiCache for Redis cache via {@code redisTemplate.opsForValue().get(key)},
     * replacing any implicit reliance on the former instance-local {@code HashMap}.</p>
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false) String sessionId) {

        // cr-java-0065 FIX: Guest name is now read from the distributed Redis session
        // via Spring Session's SessionRepository, replacing the former
        // HttpSession.getAttribute("guestName") call that was instance-local.
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            Session session = sessionRepository.findById(sessionId);
            if (session != null) {
                lastGuest = session.getAttribute("guestName");
            }
        }

        // cr-java-0067 FIX: Attempt to serve the booking from the Amazon ElastiCache
        // for Redis cache before falling back to the service layer.  The cache entry
        // was stored with a TTL in createBooking(), so it will be automatically evicted
        // after expiration, ensuring data freshness across all cluster instances.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX (Line 66 – original source):
        //   REMOVED: String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";
        // REPLACED WITH: URL retrieved at runtime from AWS Systems Manager Parameter Store.
        String inventoryUrl = getSsmParameter(inventoryUrlParameterPath);

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute
        // file path. This path does not exist inside a container image.
        String reportPath = "/var/legacy/reports/" + month + "_bookings.pdf"; // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
