package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.web.bind.annotation.*;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native REST controller for resort booking operations.
 *
 * cr-java-0065 FIX: All HTTP session state has been migrated to Amazon ElastiCache for Redis
 * via Spring Session. The javax.servlet.http.HttpSession import and all direct session
 * attribute reads/writes have been removed. Session data is now stored in a centralized
 * Redis cluster (ElastiCache), enabling stateless application instances that support
 * horizontal scaling, AWS ALB load balancing, auto-scaling, and zero-downtime failover.
 *
 * Spring Session Redis is activated by the @EnableRedisHttpSession annotation on
 * RedisSessionConfig and the spring-session-data-redis dependency in pom.xml.
 * The Redis connection is configured via spring.redis.host / spring.redis.port
 * (or SPRING_REDIS_HOST / SPRING_REDIS_PORT environment variables) pointing to the
 * ElastiCache cluster endpoint.
 *
 * Business state that was previously stored in the HTTP session (lastBooking, guestName)
 * is now returned directly in the API response and persisted via BookingService,
 * following stateless REST principles (12-factor app, factor VI: stateless processes).
 *
 * cr-java-0067 FIX: The unbounded static in-memory HashMap cache (bookingCache) has been
 * replaced with Amazon ElastiCache for Redis via Spring Data RedisTemplate. Each cache
 * entry is stored with a configurable TTL (default: 30 minutes), preventing indefinite
 * memory growth, stale data, and cache inconsistency across multiple EC2 instances.
 * The TTL is controlled by the app.cache.booking-ttl-minutes property / environment
 * variable APP_CACHE_BOOKING_TTL_MINUTES, enabling per-environment tuning without
 * code changes. All instances share the same centralized Redis cache, ensuring
 * consistent cache reads regardless of which instance handles the request.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0067 FIX: Replaces the unbounded static HashMap with a Spring Data
     * RedisTemplate backed by Amazon ElastiCache. RedisTemplate serialises booking
     * objects to Redis with a TTL, preventing indefinite memory growth and ensuring
     * cache consistency across all application instances in the cluster.
     *
     * Auto-wired by Spring Boot's RedisAutoConfiguration, which uses the
     * RedisConnectionFactory defined in RedisSessionConfig (pointing at the
     * ElastiCache primary endpoint via SPRING_REDIS_HOST / SPRING_REDIS_PORT).
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * cr-java-0067 FIX: Cache key namespace prefix used to avoid collisions with
     * Spring Session keys stored in the same ElastiCache cluster.
     */
    private static final String BOOKING_CACHE_PREFIX = "bookingCache:";

    /**
     * cr-java-0067 FIX: TTL (in minutes) for each booking cache entry.
     * Injected from the APP_CACHE_BOOKING_TTL_MINUTES environment variable or the
     * app.cache.booking-ttl-minutes application property. Defaults to 30 minutes.
     * Storing entries with a finite TTL prevents unbounded memory growth and ensures
     * stale booking data is automatically evicted from the ElastiCache cluster.
     */
    @Value("${app.cache.booking-ttl-minutes:${APP_CACHE_BOOKING_TTL_MINUTES:30}}")
    private long bookingCacheTtlMinutes;

    /**
     * cr-java-0071 FIX: AWS region used to build the SSM client for Parameter Store lookups.
     * Injected from environment variable AWS_REGION or application property aws.region.
     */
    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * cr-java-0071 FIX: SSM Parameter Store parameter name for the inventory service URL.
     * Replaces the hard-coded value "http://inventory-service.internal:8081/rooms/available".
     * Set via environment variable or application property; defaults to the SSM parameter path.
     */
    @Value("${app.ssm.inventory-url-param:/resortslite/inventory/service-url}")
    private String inventoryUrlParamName;

    /**
     * cr-java-0071 FIX: Retrieves a parameter value from AWS Systems Manager Parameter Store.
     * This eliminates hard-coded environment-specific URLs from the source code, enabling
     * environment-agnostic deployments across dev, staging, and production.
     *
     * @param paramName the SSM parameter name / path to retrieve
     * @return the decrypted parameter value from Parameter Store
     */
    private String getSsmParameter(String paramName) {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(paramName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        }
    }

    /**
     * Creates a new booking and returns the booking details in the response.
     *
     * cr-java-0065 FIX: Removed HttpSession parameter and all session.setAttribute() calls.
     * Previously, booking state (lastBooking, guestName) was stored in the HTTP session,
     * causing server affinity and data loss under AWS ALB load balancing. The booking data
     * is now returned directly in the API response body, following stateless REST principles.
     * If session-scoped user context is required in future, it must be stored via Spring
     * Session backed by Amazon ElastiCache for Redis (see RedisSessionConfig).
     *
     * cr-java-0067 FIX: Booking is cached in Amazon ElastiCache for Redis via RedisTemplate
     * with a TTL of bookingCacheTtlMinutes minutes. This replaces the unbounded static
     * HashMap that caused indefinite memory growth and cache inconsistency across instances.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        // cr-java-0065 FIX: Booking state is no longer stored in HTTP session memory.
        // The booking object is returned directly in the response, making this endpoint
        // fully stateless. Any downstream service that needs booking context must pass
        // the bookingId as a request parameter or retrieve it from BookingService.
        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0067 FIX: Store booking in Amazon ElastiCache for Redis with TTL.
        // The key is namespaced with BOOKING_CACHE_PREFIX to avoid collisions with
        // Spring Session keys. The TTL ensures automatic eviction of stale entries,
        // preventing unbounded memory growth in the ElastiCache cluster.
        // All application instances share this centralized cache, ensuring consistent
        // cache reads regardless of which EC2 instance handles subsequent requests.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("guestName", guestName);
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of a booking identified by bookingId.
     *
     * cr-java-0065 FIX: Removed HttpSession parameter and session.getAttribute("guestName")
     * call. Previously, the guest name was read from the HTTP session, which would return
     * null on any EC2 instance other than the one that created the session. The guest name
     * is now retrieved from BookingService using the bookingId, ensuring consistent results
     * regardless of which instance handles the request.
     *
     * cr-java-0067 FIX: Booking lookup first checks the Amazon ElastiCache for Redis cache
     * (via RedisTemplate) before falling back to BookingService. This replaces the
     * instance-local HashMap lookup that was invisible to other EC2 instances in the cluster.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // cr-java-0067 FIX: Attempt to retrieve booking from the centralized ElastiCache
        // Redis cache first. If the entry has expired (TTL elapsed) or was never cached,
        // fall back to BookingService for a fresh database lookup.
        String cacheKey = BOOKING_CACHE_PREFIX + bookingId;
        @SuppressWarnings("unchecked")
        Map<String, Object> cachedBooking = (Map<String, Object>) redisTemplate.opsForValue().get(cacheKey);

        // cr-java-0065 FIX: Guest name is now retrieved from the persistent BookingService
        // (backed by the database) rather than from the HTTP session. This ensures the
        // response is identical on every application instance in the cluster, enabling
        // true horizontal scaling with AWS ALB and ElastiCache-backed Spring Session.
        Map<String, Object> bookingDetails = (cachedBooking != null)
                ? cachedBooking
                : bookingService.getBookingById(bookingId);
        String lastGuest = bookingDetails != null ? (String) bookingDetails.get("guestName") : null;

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingDetails);
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: Replaced hard-coded environment URL
        // "http://inventory-service.internal:8081/rooms/available"
        // with a value retrieved from AWS Systems Manager Parameter Store.
        // The SSM parameter name is injected via the property
        // app.ssm.inventory-url-param (default: /resortslite/inventory/service-url).
        // This enables environment-agnostic deployments — the URL is stored in
        // Parameter Store per environment (dev/staging/prod) and resolved at runtime.
        String inventoryUrl = getSsmParameter(inventoryUrlParamName);

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
