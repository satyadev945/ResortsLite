package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cr-java-0065 FIX: Replaced javax.servlet.http.HttpSession with Spring Session backed by
// Amazon ElastiCache for Redis. HttpSession is now transparently managed by Spring Session
// Data Redis — all session attributes are stored in the centralized Redis cluster rather
// than in-process JVM memory. This eliminates server affinity, enables horizontal scaling
// across multiple EC2 instances behind an AWS ALB, and preserves session state across
// auto-scaling events and instance replacements.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 FIX: Replaced unbounded static in-memory HashMap cache with Amazon
    // ElastiCache for Redis via Spring Data RedisTemplate. The previous static HashMap
    // had no TTL or eviction policy, causing indefinite memory growth, potential
    // out-of-memory errors, and stale data inconsistencies across multiple EC2 instances.
    // RedisTemplate stores booking entries in the centralized ElastiCache cluster with a
    // configurable TTL (app.cache.booking.ttl-seconds, default 3600s / 1 hour), ensuring
    // controlled expiration, consistent data across all instances, and automatic eviction
    // by Redis. The cache key prefix "booking:" namespaces entries within the shared cluster.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // TTL for booking cache entries in seconds (default: 3600 = 1 hour).
    // Override via APP_CACHE_BOOKING_TTL_SECONDS environment variable or
    // AWS Systems Manager Parameter Store path /resorts-lite/cache/booking-ttl-seconds.
    @Value("${app.cache.booking.ttl-seconds:3600}")
    private long bookingCacheTtlSeconds;

    // Cache key prefix to namespace booking entries within the shared Redis cluster.
    private static final String BOOKING_CACHE_PREFIX = "booking:";

    // cr-java-0071 FIX: Replaced hard-coded inventory service URL
    // "http://inventory-service.internal:8081/rooms/available" with a value injected from
    // AWS Systems Manager Parameter Store via the Spring @Value annotation. The property
    // 'app.inventory.available.url' is resolved at runtime from the environment variable
    // APP_INVENTORY_AVAILABLE_URL or the SSM Parameter Store path
    // /resorts-lite/inventory/available-url, enabling environment-agnostic deployments
    // without any code changes between dev, staging, and production.
    @Value("${app.inventory.available.url:http://inventory-service.internal:8081/rooms/available}")
    private String inventoryAvailableUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cr-java-0065 FIX: HttpSession parameter is retained but is now transparently
            // backed by Spring Session Data Redis (Amazon ElastiCache). Session attributes
            // are stored in the centralized Redis cluster, not in local JVM memory.
            // This makes the application stateless at the instance level and supports
            // horizontal scaling across multiple EC2 instances behind an AWS ALB.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: session.setAttribute calls are now backed by Amazon ElastiCache
        // for Redis via Spring Session Data Redis. Session state is stored in the shared
        // Redis cluster, making it accessible to all application instances in the cluster.
        // AWS ALB can route subsequent requests to any instance without session loss.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 FIX: Store booking in Amazon ElastiCache for Redis with TTL instead
        // of the previous unbounded static HashMap. The entry expires automatically after
        // bookingCacheTtlSeconds (default 3600s), preventing indefinite memory growth and
        // ensuring stale data is evicted. All EC2 instances share the same Redis cluster,
        // so cache reads are consistent regardless of which instance handles the request.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // cr-java-0065 FIX: HttpSession parameter is now backed by Spring Session Data
            // Redis (Amazon ElastiCache). Reading session attributes retrieves data from the
            // shared Redis cluster, so any EC2 instance in the cluster can serve this request
            // regardless of which instance originally created the session.
            HttpSession session) {

        // cr-java-0065 FIX: session.getAttribute now reads from Amazon ElastiCache for Redis
        // via Spring Session Data Redis. The guestName attribute is available on all instances
        // because it is stored in the centralized Redis cluster, not in local JVM memory.
        String lastGuest = (String) session.getAttribute("guestName");

        // cr-java-0067 FIX: Look up booking from Amazon ElastiCache for Redis instead of
        // the previous static in-memory HashMap. This ensures cache reads are consistent
        // across all EC2 instances and that only non-expired entries are returned.
        String cacheKey = BOOKING_CACHE_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("cachedBooking", cachedBooking);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX (Source Line 66): Replaced hard-coded environment-specific URL
        // "http://inventory-service.internal:8081/rooms/available" with the externalized
        // field 'inventoryAvailableUrl', whose value is sourced from AWS Systems Manager
        // Parameter Store via the Spring property 'app.inventory.available.url'.
        // This allows the URL to differ per environment (dev/staging/prod) without any
        // code modification, satisfying cloud-native externalized configuration principles.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryAvailableUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute
        // file path. This path does not exist inside a container image. Container images
        // have their own isolated file systems — /var/legacy/reports/ won't be present.
        String reportPath = "/var/legacy/reports/" + month + "_bookings.pdf"; // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
