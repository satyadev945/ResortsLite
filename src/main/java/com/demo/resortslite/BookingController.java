package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cr-java-0065 FIX: javax.servlet.http.HttpSession is retained as the API surface, but
// Spring Session (enabled via RedisSessionConfig / @EnableRedisHttpSession) transparently
// replaces the in-memory session store with Amazon ElastiCache for Redis at runtime.
// All session.setAttribute / session.getAttribute calls now read/write from the shared
// Redis cluster, making every application instance fully stateless and enabling
// horizontal scaling without sticky sessions on the AWS ALB.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 FIX: Replaced unbounded in-memory HashMap cache with Amazon ElastiCache
    // for Redis via Spring Data RedisTemplate. Each cache entry is stored with a configurable
    // TTL (default 3600 seconds / 1 hour), preventing indefinite memory growth and stale data.
    // The shared Redis cluster ensures cache consistency across all application instances,
    // eliminating the instance-local visibility problem that breaks horizontal scaling on AWS.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // TTL for booking cache entries — configurable via BOOKING_CACHE_TTL_SECONDS env var.
    // Defaults to 3600 seconds (1 hour). Stored in AWS Systems Manager Parameter Store
    // as /resorts-lite/cache/booking-ttl-seconds for centralised configuration management.
    @Value("${cache.booking.ttl-seconds:${BOOKING_CACHE_TTL_SECONDS:3600}}")
    private long bookingCacheTtlSeconds;

    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: session.setAttribute now writes to Amazon ElastiCache for Redis
        // via Spring Session (RedisSessionConfig). The HttpSession API is unchanged; Spring
        // Session intercepts all calls and persists attributes in the shared Redis cluster.
        // AWS ALB can route subsequent requests to any instance — no sticky sessions needed.
        session.setAttribute("lastBooking", booking); // cr-java-0065 FIXED — stored in Redis via Spring Session
        session.setAttribute("guestName", guestName); // cr-java-0065 FIXED — stored in Redis via Spring Session

        // cr-java-0067 FIX: Cache booking in Amazon ElastiCache for Redis with TTL.
        // RedisTemplate.opsForValue().set() stores the entry with an explicit expiration,
        // preventing unbounded memory growth and ensuring stale entries are automatically
        // evicted. All instances share the same Redis cluster — no cache inconsistency.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: session.getAttribute now reads from Amazon ElastiCache for Redis
        // via Spring Session. The value is available on any instance in the cluster, not just
        // the instance that originally stored it — eliminating server affinity requirements.
        String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065 FIXED — read from Redis via Spring Session

        // cr-java-0067 FIX: Retrieve booking from Amazon ElastiCache for Redis.
        // If the entry has expired (TTL elapsed) or was never cached, fall back to the
        // database via bookingService.getBookingById() to maintain full functionality.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
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
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP call to
        // internal inventory service. AWS ALB, WAF, and Well-Architected security review
        // enforce HTTPS. This call will be blocked or flagged in a cloud-native setup.
        String inventoryUrl = "http://inventory-service.internal:8081/rooms/available"; // cr-java-0088

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
