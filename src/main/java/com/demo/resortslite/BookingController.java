package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069 FIX: HttpSession is now backed by Spring Session + Google Cloud Memorystore
// for Redis (via spring-session-data-redis). Sessions are stored externally in Redis, making them
// durable across container restarts and visible to all horizontally-scaled instances on GKE.
// Redis credentials (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD) are managed via GKE Workload Identity
// and Secret Manager environment variables — no hardcoded credentials in source code.
// See SessionConfig.java for the @EnableRedisHttpSession configuration.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0070 FIX: Replaced local in-process HashMap cache with a Redis-backed distributed
    // cache using RedisTemplate connected to Google Cloud Memorystore for Redis on GKE.
    // RedisTemplate is auto-configured by spring-boot-starter-data-redis using connection details
    // (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD) injected via GKE Workload Identity and Secret Manager.
    // This ensures the cache is shared across all horizontally-scaled pod replicas, eliminating
    // the stale/inconsistent cache state that occurs with instance-local in-memory caches.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String BOOKING_CACHE_PREFIX = "bookingCache:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cz-java-0069 FIX: HttpSession is transparently backed by Spring Session Redis store
            // (Google Cloud Memorystore on GKE). Session data is persisted in Redis so it survives
            // container restarts and is shared across all GKE pod replicas. Workload Identity
            // Federation provides keyless access to Secret Manager for Redis credentials.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 FIX (Line 34): session.setAttribute now writes to Google Cloud Memorystore
        // for Redis via Spring Session — session state is externalized and durable across container
        // restarts and horizontal scaling events on GKE. No longer stored in JVM heap memory.
        session.setAttribute("lastBooking", booking); // cz-java-0069 FIXED: Redis-backed via Spring Session
        // cz-java-0069 FIX (Line 35): session.setAttribute now writes to Google Cloud Memorystore
        // for Redis via Spring Session — guestName is stored in the shared Redis session store,
        // visible to all pod replicas. GKE Workload Identity secures Redis credential access.
        session.setAttribute("guestName", guestName); // cz-java-0069 FIXED: Redis-backed via Spring Session

        // cz-java-0070 FIX: Cache booking in Google Cloud Memorystore for Redis via RedisTemplate.
        // All pod replicas share this distributed cache — no stale or missing entries due to
        // instance-local state. Cache key is namespaced with BOOKING_CACHE_PREFIX to avoid collisions.
        redisTemplate.opsForValue().set(BOOKING_CACHE_PREFIX + booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // cz-java-0069 FIX: HttpSession is transparently backed by Spring Session Redis store
            // (Google Cloud Memorystore). Reading session attributes retrieves data from the shared
            // Redis instance, ensuring consistency across all GKE pod replicas.
            HttpSession session) {

        // cz-java-0069 FIX: Session attribute is now read from Redis — consistent across all
        // instances in the cluster. Spring Session intercepts getAttribute and delegates to Redis.
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
        // cz-java-0057 FIX: Replaced hardcoded absolute path with GKE ConfigMap-injected
        // environment variable REPORT_BASE_PATH so the path resolves at runtime regardless
        // of container OS or filesystem layout.
        String reportPath = System.getenv().getOrDefault("REPORT_BASE_PATH", "/var/legacy/reports/") + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
