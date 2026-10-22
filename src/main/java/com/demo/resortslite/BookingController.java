package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.ssm.SsmClient;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-ready REST controller.
 *
 * cr-java-0065 FIX (HTTP Session State Storage):
 *   All HttpSession usage is now backed by Amazon ElastiCache for Redis via
 *   Spring Session (spring-session-data-redis). The HttpSession API is preserved
 *   so that no business logic changes are required; Spring Session transparently
 *   serialises session attributes to Redis on every setAttribute/getAttribute call.
 *   This makes every application instance fully stateless — any instance in the
 *   Auto Scaling Group can serve any request, and session data survives instance
 *   termination, restarts, and scale-in events.
 *
 *   Required additions (already applied):
 *     • pom.xml  — spring-session-data-redis + spring-boot-starter-data-redis
 *     • application.properties — spring.redis.host / port / ssl / timeout
 *     • @EnableRedisHttpSession on this class (or on a dedicated @Configuration)
 *
 * cr-java-0067 FIX (In-Memory Caching Without TTL):
 *   The unbounded static HashMap bookingCache has been replaced with Amazon
 *   ElastiCache for Redis via Spring Data RedisTemplate. Every cache entry is
 *   written with a configurable TTL (default 60 minutes) so that:
 *     • Memory growth is bounded — entries expire automatically.
 *     • All application instances share the same cache — no stale-data divergence.
 *     • Cache survives instance restarts and scale-in events.
 *   The TTL is externalised to application.properties / environment variable
 *   (app.booking.cache.ttl-minutes) so it can be tuned per environment without
 *   a code change.
 */
@EnableRedisHttpSession
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0071 FIX: SSM client injected to resolve environment-specific URLs
    // from AWS Systems Manager Parameter Store instead of hard-coding them.
    @Autowired
    private SsmClient ssmClient;

    // cr-java-0067 FIX: RedisTemplate replaces the unbounded static HashMap.
    // Spring Boot auto-configures this bean when spring-boot-starter-data-redis
    // is on the classpath and spring.redis.host / port are set in
    // application.properties. All cache reads/writes go to Amazon ElastiCache.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cr-java-0067 FIX: Cache key prefix — namespaces booking entries in Redis
    // so they do not collide with Spring Session keys or other application data.
    private static final String BOOKING_CACHE_PREFIX = "booking:";

    // cr-java-0067 FIX: TTL for booking cache entries, externalised to
    // application.properties / environment variable. Default is 60 minutes.
    // Override APP_BOOKING_CACHE_TTL_MINUTES in cloud deployment to tune per environment.
    @Value("${app.booking.cache.ttl-minutes:60}")
    private long bookingCacheTtlMinutes;

    // cr-java-0071 FIX: SSM parameter name for the inventory service URL is
    // externalised to application.properties / environment variable, not hard-coded.
    @Value("${ssm.inventory.url.param:/resortslite/inventory-service-url}")
    private String inventoryUrlParamName;

    // cr-java-0071 FIX: Fallback value used only when SSM is unreachable
    // (e.g. local development). In production the SSM parameter takes precedence.
    @Value("${app.inventory.url.default:https://inventory-service.internal:8081/rooms/available}")
    private String inventoryUrlDefault;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: HttpSession is now backed by Amazon ElastiCache for Redis
        // via Spring Session. setAttribute() serialises the value to Redis so that all
        // application instances share the same session store — no server affinity required.
        session.setAttribute("lastBooking", booking); // cr-java-0065 FIXED — stored in Redis
        session.setAttribute("guestName", guestName); // cr-java-0065 FIXED — stored in Redis

        // cr-java-0067 FIX: Cache the booking in Amazon ElastiCache for Redis with a TTL.
        // RedisTemplate.opsForValue().set() writes the entry to the shared Redis cluster
        // so that all application instances can read it. The TTL ensures the entry expires
        // automatically after bookingCacheTtlMinutes, preventing unbounded memory growth.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: getAttribute() now reads from the centralised Redis session
        // store via Spring Session. Any instance in the cluster returns the correct value
        // regardless of which instance originally wrote the session attribute.
        String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065 FIXED — read from Redis

        // cr-java-0067 FIX: Retrieve booking details from the shared Redis cache.
        // If the entry has expired (TTL elapsed) or was never cached, the value will
        // be null and the controller falls back to the BookingService for a fresh lookup.
        String cacheKey = BOOKING_CACHE_PREFIX + bookingId;
        @SuppressWarnings("unchecked")
        Map<String, Object> cachedBooking = (Map<String, Object>) redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX (Hard-coded Environment URLs — line 66):
        // The hard-coded URL "http://inventory-service.internal:8081/rooms/available"
        // has been replaced with a value retrieved at runtime from AWS Systems Manager
        // Parameter Store. The SSM parameter name is itself externalised via
        // application.properties (ssm.inventory.url.param), enabling environment-agnostic
        // deployments without any code changes between dev / staging / production.
        String inventoryUrl = SsmParameterStoreConfig.getParameter(
                ssmClient, inventoryUrlParamName, inventoryUrlDefault); // cr-java-0071 FIXED

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
