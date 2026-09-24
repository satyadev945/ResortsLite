package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069 [In-Memory Session Storage / HIGH]: HttpSession is now backed by Spring Session
// Data Redis (Amazon ElastiCache). Session data is stored externally in Redis so all
// container replicas on EKS share the same session state — safe for horizontal scaling,
// container restarts, and failover. The HttpSession API is unchanged; Spring Session
// intercepts it transparently via the SessionRepositoryFilter registered by
// @EnableRedisHttpSession (see SessionConfig.java).
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0057 [File System & Storage / Critical]: Replaced hardcoded absolute file path
    // with environment variable REPORT_BASE_PATH injected via Kubernetes ConfigMap on EKS.
    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    // cz-java-0070 [Local Caches / LOW]: Replaced instance-local HashMap cache with
    // Amazon ElastiCache (Redis) via RedisTemplate. Cache entries are now shared across
    // all EKS pod replicas, surviving container restarts and horizontal scaling events.
    // REDIS_HOST and REDIS_PORT are injected via Kubernetes ConfigMap/Secret (see SessionConfig.java).
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String BOOKING_CACHE_PREFIX = "booking:";
    private static final long BOOKING_CACHE_TTL_SECONDS = 3600L;

    // cz-java-0069 [In-Memory Session Storage / HIGH]: HttpSession parameter is transparently
    // backed by Spring Session Data Redis (Amazon ElastiCache). Session attributes written
    // here are persisted to Redis and visible to every replica in the EKS cluster.
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 [In-Memory Session Storage / HIGH]: Session attributes are now stored in
        // Amazon ElastiCache (Redis) via Spring Session — shared across all EKS pod replicas.
        // Previously these were in-memory HttpSession attributes lost on container restart/scale.
        // Spring Session transparently intercepts setAttribute() and persists to Redis.
        session.setAttribute("lastBooking", booking); // cz-java-0069: Redis-backed via Spring Session
        session.setAttribute("guestName", guestName); // cz-java-0069: Redis-backed via Spring Session

        // cz-java-0070 [Local Caches / LOW]: Store booking in Amazon ElastiCache (Redis)
        // instead of the former instance-local HashMap. All EKS pod replicas share this
        // cache entry. TTL of 3600 s prevents unbounded memory growth in the cluster.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL_SECONDS, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    // cz-java-0069 [In-Memory Session Storage / HIGH]: HttpSession parameter is transparently
    // backed by Spring Session Data Redis (Amazon ElastiCache). Session attributes read
    // here are retrieved from Redis and consistent across all EKS pod replicas.
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cz-java-0069 [In-Memory Session Storage / HIGH]: Reading from Redis-backed Spring Session —
        // returns consistent data regardless of which EKS pod handles the request.
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
        // cz-java-0057 [File System & Storage / Critical]: Replaced hardcoded absolute
        // file path "/var/legacy/reports/" with environment variable REPORT_BASE_PATH
        // injected via Kubernetes ConfigMap on EKS. Eliminates container filesystem dependency.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
