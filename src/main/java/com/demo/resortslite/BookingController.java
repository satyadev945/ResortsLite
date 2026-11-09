package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cr-java-0065 FIX: HttpSession is retained as the API surface, but Spring Session
// (spring-session-data-redis) transparently replaces the in-memory session store with
// Google Cloud Memorystore for Redis at runtime. All session.setAttribute / getAttribute
// calls are now serialised to Redis, enabling stateless horizontal scaling — any instance
// in the cluster can serve any request without server affinity.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 FIX: In-memory HashMap cache replaced with Google Cloud Memorystore
    // for Redis via Spring Data Redis RedisTemplate. The cache is now shared across all
    // application instances, preventing stale data inconsistencies in horizontally scaled
    // deployments. A configurable TTL (default 3600 seconds / 1 hour) is enforced on every
    // cache entry, preventing unbounded memory growth and ensuring data freshness.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL in seconds — configurable via BOOKING_CACHE_TTL environment variable.
    // Defaults to 3600 s (1 hour) if the variable is not set.
    @Value("${booking.cache.ttl:3600}")
    private long bookingCacheTtl;

    // Redis key prefix for booking cache entries — keeps booking cache keys isolated
    // from session keys and other Redis namespaces used by the application.
    private static final String BOOKING_CACHE_PREFIX = "booking:cache:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: session.setAttribute calls now persist data to Google Cloud
        // Memorystore for Redis via Spring Session (spring-session-data-redis). The session
        // store is centralised — all application instances share the same Redis-backed session
        // data, eliminating server affinity and supporting auto-scaling and failover.
        session.setAttribute("lastBooking", booking); // stored in Memorystore for Redis
        session.setAttribute("guestName", guestName); // stored in Memorystore for Redis

        // cr-java-0067 FIX: Cache entry written to Google Cloud Memorystore for Redis with
        // an explicit TTL. The opsForValue().set() call with TimeUnit.SECONDS enforces
        // automatic expiry, preventing unbounded memory growth. All application instances
        // share this cache — reads on any instance will find the same data.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtl, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: session.getAttribute now reads from Google Cloud Memorystore
        // for Redis via Spring Session. The value is available on every instance in the
        // cluster regardless of which instance originally stored it, eliminating the
        // "null on any other instance" problem caused by in-memory HTTP session storage.
        String lastGuest = (String) session.getAttribute("guestName"); // read from Memorystore for Redis

        // cr-java-0067 FIX: Booking details are read from the Redis-backed cache. If the
        // entry has expired (TTL elapsed) or was never cached, the result will be null and
        // the caller falls back to the database via bookingService.getBookingById().
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
