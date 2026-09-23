package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * cr-java-0065 FIX: HTTP session state migrated to Amazon ElastiCache for Redis
 * via Spring Session (spring-session-data-redis).
 *
     * cr-java-0090 FIX: The Amazon Cognito JWT Bearer token is extracted from the
     * HTTP Authorization header and forwarded to BookingService.createBooking for
     * validation via CognitoTokenValidator. This replaces file-based user credential
     * lookups with cloud-native Cognito identity management.
     *
 * Previously, booking state was stored in server-local HttpSession objects, which
 * caused session affinity issues when AWS ALB distributed requests across multiple
 * EC2 instances — session data on instance A was invisible to instance B, breaking
 * auto-scaling and failover.
 *
 * With Spring Session + Redis, all session data is stored in a centralised
 * ElastiCache for Redis cluster. Every application instance reads and writes the
 * same session store, enabling fully stateless, horizontally scalable deployments.
 *
 * Configuration (application.properties / environment variables):
 *   spring.redis.host  → REDIS_HOST  (ElastiCache primary endpoint)
 *   spring.redis.port  → REDIS_PORT  (default 6379)
 *   spring.session.store-type=redis
 *   spring.session.redis.flush-mode=on_save
 *   spring.session.timeout=1800s
 *
 * cr-java-0067 FIX: In-memory bookingCache (static HashMap without TTL) replaced with
 * Amazon ElastiCache for Redis via RedisTemplate. Cache entries are stored with a
 * configurable TTL (default 30 minutes), ensuring controlled expiration, consistent
 * data across all application instances, and no unbounded memory growth.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0071: Provides environment-specific URLs fetched from
     * AWS Systems Manager Parameter Store at application startup.
     * Replaces all hard-coded environment-specific URL literals in this class.
     */
    @Autowired
    private AwsSsmParameterStoreUtil ssmParameterStoreUtil;

    /**
     * cr-java-0067 FIX: Replaces the static in-memory bookingCache HashMap (which had no
     * TTL, grew unboundedly, and was invisible to other EC2 instances) with a distributed
     * Amazon ElastiCache for Redis cache via Spring's RedisTemplate.
     *
     * Cache entries are written with a TTL of BOOKING_CACHE_TTL_MINUTES (default 30 min),
     * ensuring automatic expiration, controlled memory usage, and consistent cache state
     * across all horizontally-scaled application instances behind the AWS ALB.
     *
     * The RedisTemplate<String, Object> bean is auto-configured by Spring Boot when
     * spring-boot-starter-data-redis is on the classpath and spring.redis.host / port
     * are set (pointing to the ElastiCache primary endpoint in cloud deployments).
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** TTL for booking cache entries in Amazon ElastiCache for Redis (30 minutes). */
    private static final long BOOKING_CACHE_TTL_MINUTES = 30L;

    /** Redis key prefix for booking cache entries to avoid namespace collisions. */
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:cache:";

    /**
     * cr-java-0065 FIX: SessionRepository<S> is injected by Spring Session auto-configuration.
     * When spring-session-data-redis is on the classpath and spring.session.store-type=redis,
     * Spring Boot wires a RedisIndexedSessionRepository here automatically.
     * All session reads/writes go to Amazon ElastiCache for Redis — no server-local state.
     */
    @Autowired
    private SessionRepository sessionRepository;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session state is now stored in Amazon ElastiCache for Redis
        // cr-java-0090 FIX: Extract the Cognito JWT from the Authorization header and
        // pass it to BookingService for validation. The token is issued by Amazon Cognito
        // and replaces file-based user authentication — no user credentials are stored
        // in local files; identity is managed centrally by the Cognito User Pool.
        String cognitoToken = (authorizationHeader != null && authorizationHeader.startsWith("Bearer "))
                ? authorizationHeader.substring(7) : authorizationHeader;
        booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut, cognitoToken);

        // via Spring Session. A new distributed session is created and the booking
        // attributes are persisted centrally, visible to every application instance
        // behind the AWS ALB — eliminating server affinity and enabling horizontal scaling.
        Session session = sessionRepository.createSession();
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);
        sessionRepository.save(session);

        // cr-java-0067 FIX: Store booking in Amazon ElastiCache for Redis with a TTL of
        // BOOKING_CACHE_TTL_MINUTES (30 minutes). This replaces the previous static
        // in-memory HashMap (bookingCache) which had no expiration policy, grew
        // indefinitely, and was instance-local — invisible to other EC2 instances in the
        // cluster. Redis provides a centralised, TTL-enforced, horizontally-consistent
        // cache that works correctly across all auto-scaled application instances.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        response.put("sessionId", session.getId());
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false) String sessionId) {

        // cr-java-0065 FIX: Session data is retrieved from Amazon ElastiCache for Redis
        // via Spring Session using the sessionId passed as a request parameter.
        // This replaces the previous server-local HttpSession.getAttribute() call that
        // returned null on any instance other than the one that created the session.
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            Session session = sessionRepository.findById(sessionId);
            if (session != null) {
                lastGuest = (String) session.getAttribute("guestName");
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: Hard-coded URL "http://inventory-service.internal:8081/rooms/available"
        // replaced with a value retrieved from AWS Systems Manager Parameter Store via
        // AwsSsmParameterStoreUtil. The SSM parameter path is configured via the property
        // aws.ssm.inventory.url.param (default: /resortslite/inventory/service-url).
        // This enables environment-agnostic deployments without code changes.
        String inventoryUrl = ssmParameterStoreUtil.getInventoryServiceUrl(); // cr-java-0071

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
