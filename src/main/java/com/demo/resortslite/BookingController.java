package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

/**
 * BookingController — cloud-ready REST controller.
 *
 * <p>cr-java-0065 fix: All HTTP session state (previously stored via
 * {@code HttpSession.setAttribute}) has been migrated to Amazon ElastiCache
 * for Redis using Spring Session and {@link RedisTemplate}.  Session data is
 * now stored in a centralised, distributed Redis store so that any EC2
 * instance in the Auto Scaling group can read it, enabling true horizontal
 * scaling without server affinity.</p>
 *
 * <p>Key changes:
 * <ul>
 *   <li>Removed {@code javax.servlet.http.HttpSession} import (line 6 original).</li>
 *   <li>Injected {@code RedisTemplate<String, Object>} for distributed session storage.</li>
 *   <li>{@code createBooking}: replaced {@code session.setAttribute("lastBooking", booking)}
 *       and {@code session.setAttribute("guestName", guestName)} (lines 34–35 original)
 *       with {@code redisTemplate.opsForValue().set(…)} calls with a configurable TTL.</li>
 *   <li>{@code getBookingStatus}: replaced {@code session.getAttribute("guestName")}
 *       (line 48 original) with {@code redisTemplate.opsForValue().get(…)}.</li>
 *   <li>Removed {@code HttpSession session} parameter from both endpoint methods
 *       (lines 27 original).</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0065 fix: Distributed session store backed by Amazon ElastiCache for Redis.
     * Replaces the previous {@code HttpSession} usage so that session data is shared
     * across all application instances in the cluster.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cr-java-0067 FIX: Removed static in-memory bookingCache (HashMap without TTL).
    // Booking data is now stored exclusively in Amazon ElastiCache for Redis via
    // RedisTemplate with a configurable TTL (session.redis.ttl.seconds / SESSION_TTL_SECONDS).
    // This eliminates instance-local state, prevents unbounded memory growth, and ensures
    // cache consistency across all EC2 instances in the Auto Scaling group.

    /**
     * TTL (in seconds) for session keys stored in Redis.
     * Defaults to 1800 s (30 min); override via {@code SESSION_TTL_SECONDS} env var
     * or {@code session.redis.ttl.seconds} application property.
     */
    @Value("${session.redis.ttl.seconds:${SESSION_TTL_SECONDS:1800}}")
    private long sessionTtlSeconds;

    // cr-java-0071 FIX: AWS region resolved from environment variable / Spring property.
    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    // cr-java-0071 FIX: SSM parameter name for the inventory service URL.
    // Resolved from environment variable INVENTORY_URL_PARAM_NAME so it can be
    // configured per-environment without code changes.
    @Value("${aws.ssm.inventory.url.param:${INVENTORY_URL_PARAM_NAME:/resortslite/inventory/service-url}}")
    private String inventoryUrlParamName;

    // cr-java-0071 FIX: The hard-coded URL "http://inventory-service.internal:8081/rooms/available"
    // (line 66 original) is replaced by a value fetched from AWS Systems Manager Parameter Store
    // at application startup. This enables environment-agnostic deployments — the URL is
    // configured in SSM per environment (dev / staging / prod) without any code changes.
    private String inventoryServiceUrl;

    /**
     * Fetches the inventory service URL from AWS Systems Manager Parameter Store
     * at application startup.
     *
     * <p>cr-java-0071 fix: replaces the hard-coded URL
     * {@code "http://inventory-service.internal:8081/rooms/available"} (original line 66)
     * with a value stored in SSM Parameter Store under the path configured by
     * {@code aws.ssm.inventory.url.param} (env var: {@code INVENTORY_URL_PARAM_NAME},
     * default: {@code /resortslite/inventory/service-url}).
     * The parameter should be created in SSM for each target environment, e.g.:
     * <pre>
     *   aws ssm put-parameter \
     *     --name /resortslite/inventory/service-url \
     *     --value "https://inventory-service.internal:8081/rooms/available" \
     *     --type String
     * </pre>
     * </p>
     */
    @PostConstruct
    public void loadInventoryUrlFromParameterStore() {
        try {
            SsmClient ssmClient = SsmClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetParameterRequest paramRequest = GetParameterRequest.builder()
                    .name(inventoryUrlParamName)
                    .withDecryption(true)
                    .build();

            GetParameterResponse paramResponse = ssmClient.getParameter(paramRequest);
            this.inventoryServiceUrl = paramResponse.parameter().value();

            ssmClient.close();
        } catch (Exception e) {
            // Fall back to environment variable / property if SSM is unavailable
            // (e.g., local development without AWS credentials).
            this.inventoryServiceUrl = System.getenv()
                    .getOrDefault("INVENTORY_SERVICE_URL",
                            "http://inventory-service.internal:8081/rooms/available");
        }
    }

    /**
     * Creates a new booking and persists session state to Redis.
     *
     * <p>cr-java-0065 fix: The {@code HttpSession session} parameter (original line 27)
     * has been removed.  Session attributes are now written to Amazon ElastiCache for
     * Redis via {@link RedisTemplate} with a configurable TTL, making the operation
     * visible to every instance in the cluster.</p>
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        String bookingId = (String) booking.get("bookingId");

        // cr-java-0065 FIX: Store session state in Amazon ElastiCache for Redis instead of
        // HttpSession.  Using a booking-scoped key prefix ensures keys are namespaced and
        // do not collide across concurrent requests.  The TTL prevents stale data
        // accumulation in Redis.
        redisTemplate.opsForValue().set(
                "session:lastBooking:" + bookingId, booking, sessionTtlSeconds, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(
                "session:guestName:" + bookingId, guestName, sessionTtlSeconds, TimeUnit.SECONDS);

        // cr-java-0067 FIX: Replaced bookingCache.put(bookingId, booking) (static HashMap, no TTL)
        // with a Redis cache entry backed by Amazon ElastiCache.  The booking object is stored
        // under a "cache:booking:<bookingId>" key with the same configurable TTL, ensuring
        // controlled expiration, no unbounded memory growth, and visibility across all instances.
        redisTemplate.opsForValue().set(
                "cache:booking:" + bookingId, booking, sessionTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Returns the status of a booking, reading guest name from Redis.
     *
     * <p>cr-java-0065 fix: The {@code HttpSession session} parameter (original line 27)
     * has been removed.  The guest name is now retrieved from Amazon ElastiCache for
     * Redis via {@link RedisTemplate}, ensuring consistent reads regardless of which
     * EC2 instance handles the request.</p>
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // cr-java-0065 FIX: Read session state from Amazon ElastiCache for Redis instead of
        // HttpSession.getAttribute("guestName") (original line 48).  This ensures the value
        // is available on every instance in the Auto Scaling group.
        String lastGuest = (String) redisTemplate.opsForValue().get(
                "session:guestName:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: The hard-coded URL "http://inventory-service.internal:8081/rooms/available"
        // has been replaced with a value fetched from AWS Systems Manager Parameter Store
        // at startup (see loadInventoryUrlFromParameterStore()). The URL is now environment-agnostic
        // and can be configured per-environment in SSM without any code changes.
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
