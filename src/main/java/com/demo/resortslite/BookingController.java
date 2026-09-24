package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
// cz-java-0069 [Fixed]: Replaced in-memory javax.servlet.http.HttpSession with Spring Session
// backed by Azure Cache for Redis on AKS. Sessions are now stored externally in Redis,
// enabling stateless horizontal scaling across AKS pods without sticky sessions.
// Redis credentials are injected at runtime via the Azure Key Vault CSI Driver with
// Workload Identity — see RedisHttpSessionConfig and application.properties.
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    // cz-java-0057: Externalized absolute file path via environment variable / ConfigMap
    @Value("${app.reports.base-path:/var/reports}")
    private String reportsBasePath;

    @Autowired
    private BookingService bookingService;

    // cz-java-0070 [Fixed]: Replaced local in-process HashMap cache with Azure Cache for Redis
    // via RedisTemplate. Cache entries are now stored in the shared Redis instance so that all
    // AKS pod replicas can read/write the same cache data during horizontal scaling.
    // Redis connection details are injected at runtime via the Azure Key Vault CSI Driver
    // (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD environment variables — see application.properties).
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Redis key prefix for booking cache entries
    private static final String BOOKING_CACHE_PREFIX = "bookingCache:";

    // TTL for cached booking entries (30 minutes)
    private static final long BOOKING_CACHE_TTL_MINUTES = 30L;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cz-java-0069 [Fixed]: HttpSession is now backed by Spring Session + Azure Cache
            // for Redis (via spring-session-data-redis + @EnableRedisHttpSession in
            // RedisHttpSessionConfig). Session data is stored in Redis, not in JVM heap,
            // so all AKS pod replicas share the same session store.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 [Fixed]: Session attributes are now persisted to Azure Cache for Redis
        // via Spring Session. Any AKS pod can read this session data on subsequent requests,
        // eliminating the instance-local session problem during horizontal scaling/failover.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cz-java-0070 [Fixed]: Store booking in Azure Cache for Redis instead of the local
        // in-process HashMap. The entry is written with a TTL so stale data is automatically
        // evicted, and every AKS pod replica can access the same cached value.
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
            // cz-java-0069 [Fixed]: HttpSession is now backed by Spring Session + Azure Cache
            // for Redis. Session reads are served from the shared Redis store, not from
            // instance-local JVM memory, so this works correctly across all AKS replicas.
            HttpSession session) {

        // cz-java-0069 [Fixed]: Session attribute is now read from Azure Cache for Redis
        // via Spring Session — consistent across all pod instances in the AKS cluster.
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
        // cz-java-0057 [Fixed]: Absolute file path replaced with environment variable
        // injected via Kubernetes ConfigMap / Azure App Configuration.
        String reportPath = reportsBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
