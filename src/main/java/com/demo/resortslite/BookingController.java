package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069 FIX: javax.servlet.http.HttpSession is retained as the API contract,
// but Spring Session (spring-session-data-redis) transparently replaces the in-memory
// store with Amazon ElastiCache for Redis. No code change is needed to the HttpSession
// usage itself — Spring Session intercepts the session at the filter level and stores
// all attributes in Redis, making sessions durable across container restarts and
// horizontally-scaled EKS pods.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0057: Inject report base path from environment variable via ConfigMap
    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    // cz-java-0070 FIX: Replaced local in-memory HashMap cache (bookingCache) with
    // Amazon ElastiCache for Redis via Spring Data RedisTemplate. The local cache was
    // instance-local and invisible to other EKS pod replicas, breaking horizontal scaling.
    // RedisTemplate is auto-configured by spring-boot-starter-data-redis using connection
    // details injected at runtime via SPRING_REDIS_HOST and SPRING_REDIS_PORT environment
    // variables supplied through a Kubernetes Secret or ConfigMap pointing to ElastiCache.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cz-java-0069 FIX: HttpSession is now backed by Spring Session + Amazon ElastiCache
            // for Redis (configured via @EnableRedisHttpSession in SessionConfig). Session data
            // is stored in Redis, not in JVM heap, so it survives pod restarts and is shared
            // across all EKS pod replicas. Redis host/port are injected via environment variables
            // SPRING_REDIS_HOST and SPRING_REDIS_PORT supplied through a Kubernetes Secret/ConfigMap.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 FIX (Lines 34-35): session.setAttribute calls are now Redis-backed via
        // Spring Session. The HttpSession API is unchanged; Spring Session's
        // SessionRepositoryFilter intercepts all session operations and delegates to
        // RedisIndexedSessionRepository backed by Amazon ElastiCache for Redis on EKS.
        // Sessions survive container restarts and are shared across all pod replicas,
        // eliminating the in-memory session loss that occurs when containers restart or scale.
        session.setAttribute("lastBooking", booking);   // cz-java-0069 FIX: Redis-backed via Spring Session
        session.setAttribute("guestName", guestName);   // cz-java-0069 FIX: Redis-backed via Spring Session

        // cz-java-0070 FIX: Cache booking in Amazon ElastiCache for Redis via RedisTemplate.
        // Previously used a local HashMap (bookingCache) that was instance-local and not
        // visible to other EKS pod replicas. RedisTemplate.opsForHash() stores the booking
        // in a shared Redis hash, accessible by all horizontally-scaled container instances.
        // Redis connection details are supplied via SPRING_REDIS_HOST / SPRING_REDIS_PORT env vars.
        redisTemplate.opsForHash().put("bookingCache", booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // cz-java-0069 FIX: HttpSession is now backed by Spring Session + Amazon ElastiCache
            // for Redis. Reading session attributes retrieves data from the shared Redis store,
            // so any EKS pod replica can serve this request regardless of which pod originally
            // created the session. Redis connection details are supplied via environment variables.
            HttpSession session) {

        // cz-java-0069 FIX: session.getAttribute now reads from Redis via Spring Session,
        // eliminating the single-instance limitation of the previous in-memory HttpSession.
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
        // cz-java-0057 FIX: Replaced hardcoded absolute path "/var/legacy/reports/" with
        // environment-variable-backed field reportBasePath injected via ${REPORT_BASE_PATH}.
        // The path is now supplied by a Kubernetes ConfigMap / EKS Pod env var at runtime.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
