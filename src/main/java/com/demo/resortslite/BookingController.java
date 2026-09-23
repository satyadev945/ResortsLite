package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069 (Line 34-35 in original): In-memory HttpSession replaced with
// Spring Session backed by Amazon ElastiCache (Redis) via @EnableRedisHttpSession
// in RedisSessionConfig. The import is unchanged because Spring Session transparently
// replaces the in-memory session store with a distributed Redis store — no
// controller-level API change is needed. Session data is now durable across
// container restarts and shared across all EKS pod replicas.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0057: Absolute file path replaced with environment variable injection
    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    // cz-java-0070 FIX (Line 19): Local in-memory HashMap cache replaced with
    // Amazon ElastiCache (Redis) via RedisTemplate. The cache is now distributed
    // and shared across all EKS pod replicas, enabling safe horizontal scaling.
    // Connection details are injected via environment variables (REDIS_HOST, REDIS_PORT,
    // REDIS_PASSWORD) through application.properties / Kubernetes ConfigMap and Secrets.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cz-java-0070: Cache TTL in seconds — configurable via environment variable
    // BOOKING_CACHE_TTL_SECONDS (default: 3600 = 1 hour). Inject via EKS ConfigMap.
    @Value("${BOOKING_CACHE_TTL_SECONDS:3600}")
    private long bookingCacheTtlSeconds;

    // cz-java-0069: HttpSession parameter is now Redis-backed via Spring Session
    // (@EnableRedisHttpSession in RedisSessionConfig). Session attributes written here
    // are stored in Amazon ElastiCache and are accessible from any EKS pod, enabling
    // safe horizontal scaling and failover. Session data survives container restarts.
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 FIX (original lines 34-35): session.setAttribute calls are now
        // persisted in Amazon ElastiCache (Redis) via Spring Session, not in-memory.
        // AWS ALB sticky-session dependency is eliminated — any EKS pod can serve
        // subsequent requests for this session. Session data is not lost on restart.
        session.setAttribute("lastBooking", booking); // cz-java-0069: Redis-backed via Spring Session
        session.setAttribute("guestName", guestName); // cz-java-0069: Redis-backed via Spring Session

        // cz-java-0070 FIX: Store booking in Amazon ElastiCache (Redis) instead of
        // the former local HashMap. The key is namespaced under "bookingCache:" to
        // avoid collisions with other Redis keys. A TTL is applied so stale entries
        // are automatically evicted, preventing unbounded memory growth in ElastiCache.
        String cacheKey = "bookingCache:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    // cz-java-0069: HttpSession parameter is now Redis-backed via Spring Session.
    // Reading session attributes here retrieves data from Amazon ElastiCache,
    // so the correct guest name is returned regardless of which EKS pod handles
    // this request.
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cz-java-0069: Session attribute read from Amazon ElastiCache (Redis) —
        // no longer instance-local; consistent across all pods in the EKS cluster.
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
        // cz-java-0057: Hardcoded absolute file path replaced with environment variable REPORT_BASE_PATH
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
