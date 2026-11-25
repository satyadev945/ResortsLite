package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

// HttpSession is transparently backed by Spring Session + Amazon ElastiCache for Redis
// (see spring.session.* / spring.redis.* in application.properties), so session state is
// externalised and shared across all instances behind the load balancer.
import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIXED cr-java-0067: The instance-local, unbounded static HashMap was replaced with a
    // shared Amazon ElastiCache for Redis cache. Every entry is written with a TTL
    // (app.cache.booking.ttl-seconds, default 1 hour) so memory is bounded and all
    // instances see the same data.
    private static final String BOOKING_CACHE_PREFIX = "resortslite:booking:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${app.cache.booking.ttl-seconds:3600}")
    private long bookingCacheTtlSeconds;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED cr-java-0065: Session is managed by Spring Session and persisted in Amazon
        // ElastiCache for Redis, so it is visible to every instance behind the ALB and
        // survives instance termination. Stored values must be java.io.Serializable.
        session.setAttribute("lastBooking", new HashMap<>(booking));
        session.setAttribute("guestName", guestName);

        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        Map<String, String> cacheEntry = new HashMap<>();
        for (Map.Entry<String, Object> e : booking.entrySet()) {
            if (e.getValue() != null) {
                cacheEntry.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        redisTemplate.opsForHash().putAll(cacheKey, cacheEntry);
        redisTemplate.expire(cacheKey, Duration.ofSeconds(bookingCacheTtlSeconds));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // FIXED cr-java-0065: Session state is read from the Redis-backed Spring Session store,
        // so it resolves identically on any instance in the cluster.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
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
