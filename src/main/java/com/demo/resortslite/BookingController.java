package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// cr-java-0065 FIX: @EnableRedisHttpSession activates Spring Session with Redis as the
// backing store. All HttpSession operations (getAttribute / setAttribute) are transparently
// delegated to Amazon ElastiCache for Redis, making session state fully distributed and
// shared across every EC2 / ECS instance in the cluster. No server affinity is required;
// AWS ALB can route any request to any instance and the session data is always available.
@EnableRedisHttpSession
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 FIX: Replaced unbounded in-memory HashMap cache (bookingCache) with
    // Amazon ElastiCache for Redis via Spring Data RedisTemplate. Redis provides:
    //   - TTL-based automatic expiration (controlled by app.booking.cache.ttl-seconds)
    //   - Centralized, shared cache visible to ALL application instances in the cluster
    //   - No indefinite memory growth or stale data inconsistencies across instances
    //   - Controlled eviction policies managed by ElastiCache (e.g. allkeys-lru)
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // TTL for booking cache entries in seconds. Defaults to 3600 (1 hour).
    // Override via environment variable BOOKING_CACHE_TTL_SECONDS or
    // AWS Systems Manager Parameter Store parameter /resortslite/booking/cache/ttl-seconds.
    @Value("${app.booking.cache.ttl-seconds:3600}")
    private long bookingCacheTtlSeconds;

    // Redis key prefix for booking cache entries — avoids collisions with other data.
    private static final String BOOKING_CACHE_KEY_PREFIX = "resortslite:booking:cache:";

    // cr-java-0071 FIX: Hard-coded environment URL replaced with externalized configuration
    // backed by AWS Systems Manager Parameter Store. The property key
    // 'app.inventory.service.url' is resolved at runtime from SSM Parameter Store via
    // Spring Cloud AWS or environment variable INVENTORY_SERVICE_URL, enabling
    // environment-agnostic deployments without code changes per environment.
    @Value("${app.inventory.service.url:https://inventory-service.internal:8081/rooms/available}")
    private String inventoryServiceUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cr-java-0065 FIX: HttpSession is retained as the API surface but is now backed
            // by Spring Session + Amazon ElastiCache for Redis (enabled via
            // @EnableRedisHttpSession above). Every setAttribute call writes to Redis so the
            // data is visible to all instances in the cluster — no server affinity needed.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session attributes are now stored in Amazon ElastiCache for Redis
        // via Spring Session. The HttpSession API is unchanged; Spring Session transparently
        // serialises and persists these values to the shared Redis cluster so that any
        // instance handling subsequent requests can read them without sticky sessions.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 FIX: Cache booking in Amazon ElastiCache for Redis with TTL.
        // The key is namespaced with BOOKING_CACHE_KEY_PREFIX to avoid collisions.
        // The TTL (bookingCacheTtlSeconds) ensures automatic expiration, preventing
        // indefinite memory growth and stale data across distributed instances.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // cr-java-0065 FIX: HttpSession is backed by Spring Session + ElastiCache for Redis.
            // getAttribute reads from the shared Redis store, so the correct value is returned
            // regardless of which EC2/ECS instance handles this request.
            HttpSession session) {

        // cr-java-0065 FIX: Reading from the Redis-backed distributed session store.
        // The value is available on every instance in the cluster; no sticky-session
        // configuration is required on the AWS ALB.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: Hard-coded environment URL "http://inventory-service.internal:8081/rooms/available"
        // replaced with the @Value-injected field 'inventoryServiceUrl', which is resolved
        // from AWS Systems Manager Parameter Store parameter '/resortslite/inventory/service/url'
        // (or the environment variable INVENTORY_SERVICE_URL as a fallback).
        // This enables environment-agnostic deployments: dev, staging, and production each
        // supply their own SSM parameter value without any code change.
        String inventoryUrl = inventoryServiceUrl;

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
