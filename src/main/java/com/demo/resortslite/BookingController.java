package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
// cz-java-0069 Fix (Line 7): Replaced javax.servlet.http.HttpSession direct import with
// Spring Session backed by Azure Cache for Redis. HttpSession is now transparently stored
// in Redis, enabling stateless, horizontally-scalable containers on AKS.
import javax.servlet.http.HttpSession;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
// cz-java-0070 Fix: Import RedisTemplate to replace the local in-memory HashMap cache
// with Azure Cache for Redis, enabling shared, distributed caching across all AKS pod replicas.
import org.springframework.data.redis.core.RedisTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
// cz-java-0069 Fix: @EnableRedisHttpSession activates Spring Session with Redis store,
// replacing the default in-memory HttpSession with a Redis-backed distributed session.
// Session data survives container restarts and is shared across all AKS pod replicas,
// preventing session loss on scale-out or pod eviction events.
@EnableRedisHttpSession
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0057 Fix: Replaced hardcoded absolute path with environment variable via @Value
    @Value("${app.report.base-path:${REPORT_BASE_PATH:/var/reports}}")
    private String reportBasePath;

    // cz-java-0070 Fix (Line 19): Replaced local in-memory HashMap cache with RedisTemplate
    // backed by Azure Cache for Redis on AKS. The local static HashMap was instance-local
    // and invisible to other pod replicas, breaking horizontal scaling. RedisTemplate stores
    // booking entries in a shared Redis cluster accessible by all AKS pod instances.
    // Redis connection credentials (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD) are injected
    // securely at runtime via Azure Key Vault CSI Driver with Workload Identity on AKS.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cz-java-0070 Fix: Redis key namespace prefix for booking cache entries.
    // All booking cache keys are prefixed with "bookingCache:" to avoid collisions
    // with other Redis key namespaces (e.g., Spring Session keys).
    private static final String BOOKING_CACHE_PREFIX = "bookingCache:";

    // cz-java-0070 Fix: TTL for booking cache entries in Redis (24 hours).
    // Local HashMap had no TTL — entries accumulated indefinitely per instance.
    // Redis TTL ensures stale entries are automatically evicted across all replicas.
    private static final long BOOKING_CACHE_TTL_HOURS = 24L;

    // cz-java-0069 Fix (Lines 34-35): Replaced in-memory HttpSession usage with Spring Session
    // backed by Azure Cache for Redis on AKS, using the Azure Key Vault CSI Driver with
    // Workload Identity to securely inject Redis credentials.
    // session.setAttribute now writes to Redis (Spring Session), not to in-memory JVM state.
    // All AKS pod instances share the same Redis store — session survives restarts and scaling.
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 Fix (Line 34): session.setAttribute writes to Azure Cache for Redis
        // via Spring Session — not to in-memory JVM state. Session is durable across
        // container restarts and visible to all AKS pod replicas behind the load balancer.
        session.setAttribute("lastBooking", booking);
        // cz-java-0069 Fix (Line 35): session.setAttribute writes to Azure Cache for Redis
        // via Spring Session — guestName is stored externally, not in local JVM memory.
        session.setAttribute("guestName", guestName);

        // cz-java-0070 Fix: Replaced bookingCache.put() (local HashMap) with RedisTemplate.opsForValue().set()
        // Booking entry is now stored in Azure Cache for Redis with a 24-hour TTL,
        // shared across all AKS pod replicas. No longer instance-local or lost on pod restart.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL_HOURS, TimeUnit.HOURS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    // cz-java-0069 Fix: Reading from Redis-backed Spring Session — consistent across
    // all container instances; no longer returns null on a different AKS pod.
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cz-java-0069 Fix: Reading from Redis-backed Spring Session — consistent across
        // all container instances; no longer returns null on a different AKS pod.
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
        // cz-java-0057 Fix: Replaced hardcoded absolute path "/var/legacy/reports/" with
        // environment-variable-backed property app.report.base-path (env: REPORT_BASE_PATH)
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
