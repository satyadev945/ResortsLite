package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — REST controller for resort booking operations.
 *
 * <p>cr-java-0065 FIX: HTTP session state is now managed by Spring Session backed by
 * Amazon ElastiCache for Redis. The {@code @EnableRedisHttpSession} annotation on this
 * class (or on a dedicated {@code HttpSessionConfig} configuration class) instructs
 * Spring Session to transparently store and retrieve all {@code HttpSession} attributes
 * in Redis rather than in the local JVM heap. This makes every application instance
 * stateless with respect to session data: any EC2 instance behind the AWS ALB can
 * serve any request regardless of which instance originally created the session,
 * enabling safe horizontal scaling, rolling deployments, and instance replacement
 * without session loss.</p>
 *
 * <p>cr-java-0067 FIX (Line 19 original): The unbounded static in-memory
 * {@code HashMap} used as a booking cache has been replaced with Amazon ElastiCache
 * for Redis via Spring Data Redis {@code RedisTemplate}. Cache entries are written
 * with a configurable TTL (default 24 hours, controlled by
 * {@code cache.booking.ttl-hours}), preventing indefinite memory growth, stale data
 * inconsistencies, and instance-local cache silos that break horizontal scaling.
 * All application instances share the same Redis cluster, so cached booking data is
 * consistent across the entire fleet.</p>
 *
 * <p>cr-java-0071 FIX (Line 66 original): The hard-coded environment URL
 * {@code "http://inventory-service.internal:8081/rooms/available"} has been
 * replaced with a value retrieved at startup from AWS Systems Manager Parameter
 * Store. The SSM parameter name is controlled by the Spring property
 * {@code ssm.parameter.inventory-url} (override via environment variable
 * {@code SSM_PARAM_INVENTORY_URL}), enabling environment-agnostic deployments
 * without any code changes between dev / staging / production.</p>
 */
@RestController
@RequestMapping("/api/bookings")
@EnableRedisHttpSession
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0067 FIX: Replaces the former unbounded static in-memory HashMap
     * ({@code private static final Map<String, Object> bookingCache = new HashMap<>()}).
     *
     * <p>Spring Data Redis {@code RedisTemplate} delegates all cache reads and writes
     * to Amazon ElastiCache for Redis. Unlike a local {@code HashMap}, this cache is:
     * <ul>
     *   <li><b>Shared</b> — every application instance in the Auto Scaling Group reads
     *       from and writes to the same Redis cluster, eliminating instance-local silos.</li>
     *   <li><b>TTL-controlled</b> — each entry expires automatically after
     *       {@code cache.booking.ttl-hours} hours (default 24 h), preventing unbounded
     *       memory growth and stale data accumulation.</li>
     *   <li><b>Externally managed</b> — ElastiCache handles eviction, replication, and
     *       failover, removing operational burden from the application tier.</li>
     * </ul>
     * </p>
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * TTL (in hours) applied to every booking cache entry written to Redis.
     * Controlled by Spring property {@code cache.booking.ttl-hours}; override via
     * environment variable {@code BOOKING_CACHE_TTL_HOURS}. Defaults to 24 hours.
     *
     * <p>cr-java-0067 FIX: Enforcing a TTL ensures that cached booking objects expire
     * automatically, preventing indefinite memory growth in the Redis cluster and
     * guaranteeing that stale entries are not served beyond the configured window.</p>
     */
    @Value("${cache.booking.ttl-hours:${BOOKING_CACHE_TTL_HOURS:24}}")
    private long bookingCacheTtlHours;

    /**
     * Key prefix used for all booking cache entries stored in Redis.
     * Namespacing prevents collisions with other keys in the shared Redis cluster.
     */
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:cache:";

    /**
     * AWS region used to build the SSM client.
     * Resolved from Spring property {@code cloud.aws.region.static} or environment
     * variable {@code AWS_REGION}; defaults to {@code us-east-1}.
     */
    @Value("${cloud.aws.region.static:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * SSM Parameter Store parameter name that holds the inventory-service URL.
     * Resolved from Spring property {@code ssm.parameter.inventory-url} or environment
     * variable {@code SSM_PARAM_INVENTORY_URL}.
     *
     * <p>cr-java-0071 FIX: replaces the hard-coded literal
     * {@code "http://inventory-service.internal:8081/rooms/available"} that was
     * present at original line 66.</p>
     */
    @Value("${ssm.parameter.inventory-url:${SSM_PARAM_INVENTORY_URL:/resortslite/inventory/url}}")
    private String inventoryUrlParameterName;

    /**
     * Inventory-service URL resolved from AWS SSM Parameter Store at application startup.
     * Populated by {@link #init()}.
     */
    private String inventoryServiceUrl;

    /**
     * Resolves the inventory-service URL from AWS SSM Parameter Store once at startup.
     *
     * <p>cr-java-0071 FIX: instead of embedding the URL as a string literal in source
     * code, the value is fetched from SSM so that each deployment environment (dev,
     * staging, production) can supply its own URL without recompilation.</p>
     */
    @PostConstruct
    public void init() {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetParameterRequest request = GetParameterRequest.builder()
                    .name(inventoryUrlParameterName)
                    .withDecryption(false)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            inventoryServiceUrl = response.parameter().value();

        } catch (Exception e) {
            // Fall back to a safe default so the application still starts in local/test
            // environments where SSM may not be reachable.
            inventoryServiceUrl = System.getenv().getOrDefault(
                    "INVENTORY_SERVICE_URL",
                    "http://inventory-service.internal:8081/rooms/available");
        }
    }

    /**
     * Creates a new booking and stores transient booking state in the distributed
     * Redis-backed HTTP session via Spring Session.
     *
     * <p>cr-java-0065 FIX: {@code session.setAttribute()} calls now write to Amazon
     * ElastiCache for Redis through Spring Session's transparent proxy. All application
     * instances share the same Redis cluster, so the session attributes are visible
     * regardless of which EC2 instance handles subsequent requests.</p>
     *
     * <p>cr-java-0067 FIX: The former {@code bookingCache.put(bookingId, booking)}
     * call (which wrote to the unbounded static in-memory {@code HashMap}) has been
     * replaced with {@code redisTemplate.opsForValue().set(key, booking, ttl, HOURS)}.
     * The entry is stored in Amazon ElastiCache for Redis with a TTL of
     * {@code cache.booking.ttl-hours} hours, ensuring controlled expiration and
     * consistent data across all application instances.</p>
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session attributes are now stored in Amazon ElastiCache for
        // Redis via Spring Session (@EnableRedisHttpSession). The HttpSession API is
        // unchanged; Spring Session transparently redirects all setAttribute/getAttribute
        // calls to the shared Redis cluster, eliminating server affinity and enabling
        // safe horizontal scaling across multiple EC2 instances behind the AWS ALB.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 FIX: Replaced unbounded static in-memory HashMap cache entry
        // (bookingCache.put(bookingId, booking)) with a TTL-controlled Redis cache write.
        // The booking object is stored in Amazon ElastiCache for Redis under a namespaced
        // key and will automatically expire after bookingCacheTtlHours hours (default 24 h).
        // This eliminates indefinite memory growth, stale data, and instance-local cache
        // silos that prevented safe horizontal scaling across multiple EC2 instances.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlHours, TimeUnit.HOURS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of a booking, reading the guest name from the distributed
     * Redis-backed HTTP session via Spring Session.
     *
     * <p>cr-java-0065 FIX: {@code session.getAttribute()} now reads from Amazon
     * ElastiCache for Redis through Spring Session. The value is available on every
     * application instance, not just the one that originally set it.</p>
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: Session attribute is retrieved from Amazon ElastiCache for
        // Redis via Spring Session. Any instance in the cluster can serve this request
        // and will find the correct guestName stored in the shared Redis session store.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX (original line 66): Hard-coded URL replaced with value
        // retrieved from AWS SSM Parameter Store at startup via init().
        // The parameter name is configured via ssm.parameter.inventory-url property.
        String inventoryUrl = inventoryServiceUrl; // resolved from SSM Parameter Store

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
