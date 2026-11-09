package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// cz-java-0069: Replaced javax.servlet.http.HttpSession (in-memory session storage) with
// Spring Session backed by Amazon ElastiCache for Redis. Session state is now stored
// externally and shared across all container instances, enabling horizontal scaling and
// surviving container restarts on EKS. IRSA is used for secure, credential-free access.
import org.springframework.data.redis.core.RedisTemplate;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0069: RedisTemplate used to store session-scoped data externally in
    // Amazon ElastiCache for Redis instead of in-memory HttpSession. All EKS pod
    // replicas share the same session store, preventing data loss on container restart.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${app.reports.base-path:#{systemEnvironment['REPORTS_BASE_PATH'] ?: '/mnt/reports'}}")
    private String reportsBasePath;

    // cz-java-0070: Removed in-process local cache (HashMap) that was instance-local and
    // invisible to other EKS pod replicas, breaking horizontal scaling. Booking cache
    // entries are now stored in Amazon ElastiCache for Redis via RedisTemplate, making
    // the cache shared across all container instances. Connection details are injected
    // via Kubernetes ConfigMaps and Secrets (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD)
    // with IRSA-secured access to ElastiCache on EKS.
    private static final String BOOKING_CACHE_KEY = "bookingCache";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "default-session") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 (Line 34 fix): Replaced session.setAttribute("lastBooking", booking)
        // — in-memory HttpSession — with RedisTemplate storing session state in Amazon
        // ElastiCache for Redis. Session data is now visible to all EKS pod replicas and
        // survives container restarts, enabling true horizontal scaling behind AWS ALB.
        redisTemplate.opsForHash().put("session:" + sessionId, "lastBooking", booking);

        // cz-java-0069 (Line 35 fix): Replaced session.setAttribute("guestName", guestName)
        // — in-memory HttpSession — with RedisTemplate storing guest name in Amazon
        // ElastiCache for Redis. Consistent session state is maintained across all replicas.
        redisTemplate.opsForHash().put("session:" + sessionId, "guestName", guestName);

        // cz-java-0070: Replaced bookingCache.put(...) on the local HashMap with a Redis
        // hash operation so the booking cache is stored in Amazon ElastiCache for Redis.
        // All EKS pod replicas share this distributed cache, eliminating the stale/missing
        // data problem that occurred when requests were load-balanced to different instances.
        redisTemplate.opsForHash().put(BOOKING_CACHE_KEY, (String) booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "default-session") String sessionId) {

        // cz-java-0069: Reading guest name from Amazon ElastiCache for Redis via Spring
        // Session instead of in-memory HttpSession. This ensures consistent session state
        // across all container replicas in the EKS cluster.
        String lastGuest = (String) redisTemplate.opsForHash().get("session:" + sessionId, "guestName");

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
        // cz-java-0057: Replaced hardcoded absolute path with environment-variable-backed value
        // injected via Kubernetes ConfigMap (REPORTS_BASE_PATH env var or app.reports.base-path property)
        String reportPath = reportsBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
