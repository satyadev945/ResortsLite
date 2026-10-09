package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate; // cz-java-0070: RedisTemplate replaces local HashMap cache with Amazon ElastiCache (Redis) for distributed, horizontally-scalable caching on EKS
import org.springframework.web.bind.annotation.*;
import org.springframework.session.Session; // cz-java-0069: Spring Session replaces in-memory HttpSession for Redis-backed distributed sessions

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit; // cz-java-0070: TTL support for Redis cache entries

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    @Autowired
    private BookingService bookingService;

    // cz-java-0070 FIXED: Local in-memory HashMap cache replaced with Amazon ElastiCache (Redis)
    // via RedisTemplate. Cache entries are now shared across all EKS pod replicas, survive
    // container restarts/scaling events, and support TTL-based expiry to prevent stale data.
    // REDIS_HOST and REDIS_PORT are injected via Kubernetes ConfigMap/Secret (IRSA-secured).
    @Autowired
    private RedisTemplate<String, Object> redisTemplate; // cz-java-0070: distributed cache backed by Amazon ElastiCache (Redis)

    // cz-java-0070: Cache key prefix for booking entries stored in ElastiCache (Redis)
    private static final String BOOKING_CACHE_PREFIX = "booking:"; // cz-java-0070

    // cz-java-0070: TTL for booking cache entries in ElastiCache (Redis), injected via ConfigMap
    @Value("${BOOKING_CACHE_TTL_SECONDS:3600}")
    private long bookingCacheTtlSeconds; // cz-java-0070: configurable TTL via BOOKING_CACHE_TTL_SECONDS env var

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) { // cz-java-0069: HttpSession is transparently backed by Spring Session + Amazon ElastiCache (Redis) via @EnableRedisHttpSession

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 FIXED: Session state is now stored in Amazon ElastiCache (Redis) via
        // Spring Session (@EnableRedisHttpSession on ResortsLiteApplication), ensuring session
        // data is shared across all EKS container instances and survives pod restarts/scaling.
        // HttpSession is transparently replaced by a Redis-backed session — no API change needed.
        session.setAttribute("lastBooking", booking); // cz-java-0069: persisted to Amazon ElastiCache (Redis) via Spring Session — not in-memory
        session.setAttribute("guestName", guestName); // cz-java-0069: persisted to Amazon ElastiCache (Redis) via Spring Session — not in-memory

        // cz-java-0070 FIXED: Booking stored in Amazon ElastiCache (Redis) via RedisTemplate
        // instead of local HashMap. All EKS pod replicas share the same cache, preventing
        // cache misses on horizontally-scaled deployments. TTL prevents unbounded memory growth.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId"); // cz-java-0070
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS); // cz-java-0070: stored in ElastiCache (Redis) with TTL

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) { // cz-java-0069: HttpSession is transparently backed by Spring Session + Amazon ElastiCache (Redis) via @EnableRedisHttpSession

        // cz-java-0069 FIXED: Session attribute is now read from Amazon ElastiCache (Redis)
        // via Spring Session — consistent across all EKS pod replicas regardless of which
        // instance handled the original request.
        String lastGuest = (String) session.getAttribute("guestName"); // cz-java-0069: retrieved from Amazon ElastiCache (Redis) via Spring Session

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
        // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute
        // file path replaced with environment variable REPORT_BASE_PATH injected via
        // Kubernetes ConfigMap for container-portable filesystem access.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf"; // cz-java-0057 fixed

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
