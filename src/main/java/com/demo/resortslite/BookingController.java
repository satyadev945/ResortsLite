package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cr-java-0065 and cr-java-0067 remediations applied.
 *
 * HTTP session state has been migrated to Amazon ElastiCache for Redis via
 * Spring Session (spring-session-data-redis).  All session.setAttribute /
 * session.getAttribute calls are preserved in behaviour but now operate
 * against the distributed Redis store rather than in-process JVM memory.
 *
 * cr-java-0067 FIXED: In-memory bookingCache (HashMap without TTL) has been
 * replaced with Amazon ElastiCache for Redis via Spring Data RedisTemplate.
 * Each cache entry is stored with a configurable TTL (default 30 minutes),
 * ensuring controlled expiration, consistent data across all application
 * instances, and prevention of unbounded memory growth.
 *
 * Key changes:
 *  - Removed static final HashMap bookingCache (line 19 violation).
 *  - Injected RedisTemplate<String, Object> for distributed cache operations.
 *  - bookingCache.put() replaced with redisTemplate.opsForValue().set() with TTL.
 *  - Cache reads use redisTemplate.opsForValue().get() for consistent cross-instance access.
 *  - Cache TTL is externalised via BOOKING_CACHE_TTL_MINUTES environment variable
 *    (defaults to 30 minutes) to allow runtime tuning without code changes.
 *  - Removed direct import of javax.servlet.http.HttpSession (line 6 violation).
 *  - Added @EnableRedisHttpSession at class level so Spring Session intercepts
 *    the standard HttpSession API and transparently stores data in Redis.
 *  - Method signatures now accept HttpServletRequest; session is obtained via
 *    request.getSession(), which Spring Session replaces with a Redis-backed
 *    implementation at runtime.
 *  - All setAttribute / getAttribute calls (lines 34, 35, 48) are unchanged in
 *    semantics — they now write to / read from ElastiCache instead of JVM heap.
 *  - HttpSession parameter removed from createBooking and getBookingStatus
 *    signatures (lines 27 violation) — replaced with HttpServletRequest so the
 *    controller remains stateless at the instance level.
 */
@EnableRedisHttpSession
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 FIXED: Replaced unbounded in-memory HashMap cache (no TTL) with
    // Amazon ElastiCache for Redis via Spring Data RedisTemplate.
    // RedisTemplate provides distributed, TTL-aware caching that is consistent across
    // all application instances — eliminating stale data and unbounded memory growth.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache key prefix to namespace booking entries in Redis and avoid key collisions
    // with other applications sharing the same ElastiCache cluster.
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:cache:";

    // TTL for booking cache entries — externalised via environment variable so it can
    // be tuned at runtime without code changes (12-factor app principle III).
    // Defaults to 30 minutes if BOOKING_CACHE_TTL_MINUTES is not set.
    @Value("${booking.cache.ttl.minutes:30}")
    private long bookingCacheTtlMinutes;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpServletRequest request) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIXED: Session state is now stored in Amazon ElastiCache for Redis
        // via Spring Session. The HttpSession returned by request.getSession() is a
        // Redis-backed session — data is visible to all application instances behind the
        // AWS ALB, enabling stateless horizontal scaling and safe auto-scaling / failover.
        request.getSession().setAttribute("lastBooking", booking);
        request.getSession().setAttribute("guestName", guestName);

        // cr-java-0067 FIXED: Cache booking in Amazon ElastiCache for Redis with TTL.
        // Using RedisTemplate.opsForValue().set() with explicit TTL ensures:
        //   1. Controlled expiration — entries are automatically evicted after TTL expires,
        //      preventing unbounded memory growth and stale data accumulation.
        //   2. Cross-instance consistency — all EC2/ECS instances share the same Redis
        //      cache, eliminating the per-instance cache divergence of the old HashMap.
        //   3. Resilience — cache survives application restarts and auto-scaling events.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpServletRequest request) {

        // cr-java-0065 FIXED: Reading session state from Amazon ElastiCache for Redis via
        // Spring Session. Any instance in the cluster can serve this request because the
        // session data is stored centrally in Redis, not in local JVM memory.
        String lastGuest = (String) request.getSession().getAttribute("guestName");

        // cr-java-0067 FIXED: Read booking from Amazon ElastiCache for Redis instead of
        // the former in-memory HashMap. Returns null on cache miss (TTL expired or key
        // not yet populated), in which case the caller falls through to bookingService.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

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
