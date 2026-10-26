package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069: javax.servlet.http.HttpSession is retained as the API surface, but its
// in-memory implementation is transparently replaced by Spring Session backed by
// Amazon ElastiCache for Redis (configured in SessionConfig.java via @EnableRedisHttpSession).
// All session.setAttribute / session.getAttribute calls are now delegated to Redis,
// making session state durable across EKS pod restarts and consistent across all replicas.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
// cz-java-0069: @EnableRedisHttpSession has been moved to SessionConfig.java (a dedicated
// @Configuration class). Placing it on a @RestController is incorrect — configuration
// annotations belong on @Configuration classes to ensure proper Spring context lifecycle.
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0057: Replaced hardcoded absolute file path with environment variable injection
    @Value("${app.report.base-path:#{systemEnvironment['REPORT_BASE_PATH'] ?: '/var/reports'}}")
    private String reportBasePath;

    // cz-java-0070 FIX (Line 19): Replaced local in-memory HashMap cache with Amazon
    // ElastiCache for Redis via RedisTemplate. The previous static HashMap was instance-local
    // and invisible to other EKS pod replicas, breaking horizontal scaling. RedisTemplate
    // connects to Amazon ElastiCache for Redis using REDIS_HOST / REDIS_PORT env vars
    // (configured in application.properties and injected via Kubernetes ConfigMap/Secrets).
    // IRSA (IAM Roles for Service Accounts) is used for secure, credential-free access.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cz-java-0070: Cache TTL in seconds — override via BOOKING_CACHE_TTL_SECONDS env var
    // (default: 3600 seconds / 1 hour). Injected via Kubernetes ConfigMap in EKS Pod spec.
    @Value("${app.cache.booking.ttl-seconds:${BOOKING_CACHE_TTL_SECONDS:3600}}")
    private long bookingCacheTtlSeconds;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cz-java-0069: HttpSession is now backed by Amazon ElastiCache for Redis via
            // Spring Session (spring-session-data-redis + SessionConfig.java). Session data
            // is stored externally in Redis, not in JVM heap, enabling safe horizontal
            // scaling on EKS — all pod replicas share the same Redis-backed session store.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 FIX (Lines 34-35): session.setAttribute now writes to Amazon ElastiCache
        // for Redis (not JVM in-memory). All EKS pod replicas share the same Redis-backed
        // session store, so booking state is consistent across instances and survives pod restarts.
        // Before: session stored in JVM heap — lost on container restart or on a different pod.
        // After:  session stored in Amazon ElastiCache for Redis via Spring Session — durable
        //         and shared across all EKS pod replicas using IRSA for secure access.
        session.setAttribute("lastBooking", booking); // cz-java-0069: Redis-backed via Spring Session
        session.setAttribute("guestName", guestName); // cz-java-0069: Redis-backed via Spring Session

        // cz-java-0070 FIX: Store booking in Amazon ElastiCache for Redis via RedisTemplate.
        // Before: bookingCache.put(...) stored data in a static HashMap — instance-local,
        //         invisible to other EKS pod replicas, lost on container restart.
        // After:  redisTemplate.opsForValue().set(...) stores data in Amazon ElastiCache for
        //         Redis with a TTL, shared across all EKS pod replicas and durable across
        //         pod restarts. Connection details injected via REDIS_HOST / REDIS_PORT env vars.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set(
                "booking:" + bookingId,
                booking,
                bookingCacheTtlSeconds,
                TimeUnit.SECONDS
        );

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // cz-java-0069: HttpSession is now backed by Amazon ElastiCache for Redis via
            // Spring Session. Reads are served from the shared Redis store, not from
            // instance-local JVM memory, so any EKS pod can serve this request correctly.
            HttpSession session) {

        // cz-java-0069: session.getAttribute now reads from Amazon ElastiCache for Redis.
        // The value is available on every EKS pod replica, eliminating the null-on-other-instance
        // problem that existed with the previous in-memory HttpSession.
        String lastGuest = (String) session.getAttribute("guestName");

        // cz-java-0070 FIX: Read booking from Amazon ElastiCache for Redis via RedisTemplate.
        // Before: bookingCache.get(bookingId) read from instance-local HashMap — returned null
        //         on any pod other than the one that created the booking.
        // After:  redisTemplate.opsForValue().get(...) reads from the shared Redis cluster,
        //         returning the correct cached value regardless of which EKS pod handles the request.
        @SuppressWarnings("unchecked")
        Map<String, Object> cachedBooking = (Map<String, Object>) redisTemplate.opsForValue()
                .get("booking:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("cachedBooking", cachedBooking);
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
        // cz-java-0057: Replaced hardcoded absolute file path "/var/legacy/reports/" with
        // environment variable injected via @Value / REPORT_BASE_PATH env var
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
