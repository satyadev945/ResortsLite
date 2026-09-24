package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

/**
 * BookingController — cloud-native session management via Spring Session + Amazon ElastiCache for Redis.
 *
 * <p>cr-java-0065 FIX: All HTTP session state (lastBooking, guestName) is now stored in
 * Amazon ElastiCache for Redis through Spring Session. The {@code @EnableRedisHttpSession}
 * annotation on this class activates Spring Session's Redis-backed {@link HttpSession}
 * implementation, replacing the default in-memory, server-local session store.
 * This makes every application instance stateless: any EC2 instance in the Auto Scaling
 * Group can serve any request because session data is read from and written to the shared
 * Redis cluster rather than local JVM heap. AWS ALB sticky sessions are no longer required,
 * horizontal scaling and failover work transparently, and session data survives instance
 * termination.</p>
 *
 * <p>cr-java-0067 FIX: The previous unbounded in-memory {@code HashMap} cache
 * ({@code bookingCache}) has been replaced with Amazon ElastiCache for Redis via
 * Spring's {@link RedisTemplate}. Cache entries are written with an explicit TTL
 * (default 30 minutes, configurable via the {@code BOOKING_CACHE_TTL_MINUTES}
 * environment variable), preventing indefinite memory growth and stale-data
 * inconsistencies across multiple instances. All instances share the same Redis
 * cluster, so cache reads and writes are consistent regardless of which EC2 instance
 * handles the request.</p>
 */
@EnableRedisHttpSession
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0067 FIX: RedisTemplate replaces the previous unbounded in-memory HashMap cache.
     *
     * <p>Spring Boot auto-configures this bean using the {@code spring.redis.host} and
     * {@code spring.redis.port} properties (backed by the {@code REDIS_HOST} and
     * {@code REDIS_PORT} environment variables), which point to the Amazon ElastiCache
     * for Redis primary endpoint. Cache entries are stored with an explicit TTL so that
     * memory growth is bounded and stale data is automatically evicted.</p>
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * Cache key prefix used to namespace booking entries in Redis.
     * Prevents key collisions with other data stored in the same Redis instance.
     */
    private static final String BOOKING_CACHE_PREFIX = "booking:";

    /**
     * TTL (in minutes) for booking cache entries in Redis.
     * Resolved from the {@code BOOKING_CACHE_TTL_MINUTES} environment variable;
     * defaults to 30 minutes when the variable is not set.
     */
    private static final long BOOKING_CACHE_TTL_MINUTES =
            System.getenv("BOOKING_CACHE_TTL_MINUTES") != null
                    ? Long.parseLong(System.getenv("BOOKING_CACHE_TTL_MINUTES"))
                    : 30L;

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * <p>cr-java-0071 FIX: All hard-coded environment-specific URLs are resolved at runtime
     * via SSM Parameter Store, enabling environment-agnostic deployments. The AWS region is
     * read from the {@code AWS_REGION} environment variable (default: {@code us-east-1}).</p>
     *
     * @param parameterName the SSM parameter path (e.g. "/resortslite/inventory/url")
     * @param defaultValue  fallback value used when the parameter cannot be retrieved
     * @return the resolved parameter value, or {@code defaultValue} on error
     */
    private String getSsmParameter(String parameterName, String defaultValue) {
        try {
            String awsRegion = System.getenv("AWS_REGION") != null
                    ? System.getenv("AWS_REGION")
                    : "us-east-1";
            SsmClient ssmClient = SsmClient.builder()
                    .region(Region.of(awsRegion))
                    .build();
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session attributes are now stored in Amazon ElastiCache for Redis
        // via Spring Session (activated by @EnableRedisHttpSession on this class).
        // The HttpSession API is unchanged — Spring Session transparently intercepts
        // setAttribute/getAttribute calls and delegates to the Redis-backed session store.
        // Session data is shared across all EC2 instances, enabling stateless horizontal
        // scaling without AWS ALB sticky sessions.
        session.setAttribute("lastBooking", booking); // cr-java-0065 FIXED — stored in Redis
        session.setAttribute("guestName", guestName); // cr-java-0065 FIXED — stored in Redis

        // cr-java-0067 FIX: Cache the booking in Amazon ElastiCache for Redis via RedisTemplate.
        // The entry is stored with an explicit TTL (BOOKING_CACHE_TTL_MINUTES) so that memory
        // growth is bounded and stale data is automatically evicted. All application instances
        // share the same Redis cluster, ensuring cache consistency across horizontal scaling.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: Session attribute is now read from Amazon ElastiCache for Redis
        // via Spring Session. Any EC2 instance in the cluster can serve this request and
        // retrieve the correct guestName regardless of which instance handled /create.
        String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065 FIXED — read from Redis

        // cr-java-0067 FIX: Booking details are read from Amazon ElastiCache for Redis.
        // If the cache entry has expired (TTL elapsed) or is absent, fall back to the
        // BookingService (database) to retrieve the booking, ensuring correctness.
        String cacheKey = BOOKING_CACHE_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX (Line 66): Replaced hard-coded environment-specific URL
        // "http://inventory-service.internal:8081/rooms/available" with a value retrieved
        // at runtime from AWS Systems Manager Parameter Store under the path
        // /resortslite/inventory/url. This enables environment-agnostic deployments —
        // the same binary can be promoted from dev → staging → production by updating
        // the SSM parameter value without any code or configuration file changes.
        String inventoryUrl = getSsmParameter(
                "/resortslite/inventory/url",
                "http://inventory-service.internal:8081/rooms/available");

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
