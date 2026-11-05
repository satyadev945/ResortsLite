package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// cz-java-0069 (Line 6): Replaced javax.servlet.http.HttpSession with Spring Session
// abstraction backed by Amazon ElastiCache (Redis) on EKS. Sessions are now stored
// externally, surviving container restarts and shared across all EKS pod replicas.
// IRSA (IAM Roles for Service Accounts) is used for secure, credential-free access.
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

// cz-java-0070 (Line 19): Replaced local in-process HashMap cache with Amazon ElastiCache
// (Redis) via RedisTemplate. The local HashMap was instance-local and invisible to other
// EKS pod replicas, breaking horizontal scaling. Redis provides a shared, distributed
// cache accessible by all container instances in the EKS cluster.
// REDIS_HOST and REDIS_PORT are injected via Kubernetes ConfigMap / Secret on EKS.
// IRSA (IAM Roles for Service Accounts) is used for secure, credential-free access.
import org.springframework.data.redis.core.RedisTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0069: SessionRepository is backed by Spring Session Data Redis,
    // which stores sessions in Amazon ElastiCache (Redis) — not in JVM heap.
    // All EKS pod replicas share the same Redis-backed session store.
    @Autowired
    private SessionRepository sessionRepository;

    // cz-java-0070: RedisTemplate replaces the local HashMap bookingCache.
    // Booking entries are stored in Amazon ElastiCache (Redis) with a TTL,
    // ensuring all EKS pod replicas share the same distributed cache state.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cz-java-0070: Cache key prefix for booking entries stored in Redis.
    private static final String BOOKING_CACHE_PREFIX = "bookingCache:";

    // cz-java-0070: TTL for cached booking entries (1 hour). Configurable via
    // BOOKING_CACHE_TTL_SECONDS environment variable injected via Kubernetes ConfigMap.
    @Value("${BOOKING_CACHE_TTL_SECONDS:3600}")
    private long bookingCacheTtlSeconds;

    // cz-java-0057: Absolute file path replaced with environment variable injection.
    // REPORT_BASE_PATH is injected via Kubernetes ConfigMap / EKS Pod env spec.
    @Value("${REPORT_BASE_PATH:/reports}")
    private String reportBasePath;

    // cz-java-0069 (Lines 34-35): Removed HttpSession parameter. Session state is now managed
    // via Spring Session / ElastiCache Redis using a session ID passed in the request header
    // (X-Auth-Token) or cookie, enabling stateless, horizontally-scalable EKS deployments.
    // Before fix (Line 34): session.setAttribute("lastBooking", booking);  // HttpSession in-memory
    // Before fix (Line 35): session.setAttribute("guestName", guestName);  // HttpSession in-memory
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Auth-Token", required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 (Line 34): session.setAttribute("lastBooking", booking) replaced —
        // cz-java-0069 (Line 35): session.setAttribute("guestName", guestName) replaced —
        // Session attributes are now stored in Amazon ElastiCache (Redis) via Spring Session.
        // All EKS pod replicas share the same Redis-backed session store, eliminating
        // sticky-session requirements and supporting zero-downtime rolling updates.
        if (sessionId != null && !sessionId.isEmpty()) {
            Session session = sessionRepository.findById(sessionId);
            if (session == null) {
                session = sessionRepository.createSession();
            }
            session.setAttribute("lastBooking", booking);   // cz-java-0069: stored in Redis
            session.setAttribute("guestName", guestName);   // cz-java-0069: stored in Redis
            sessionRepository.save(session);
        }

        // cz-java-0070: Replaced local HashMap.put() with RedisTemplate.opsForValue().set().
        // Booking is stored in Amazon ElastiCache (Redis) with a TTL, shared across all
        // EKS pod replicas. Before fix (Line 65): bookingCache.put(bookingId, booking)
        // was instance-local and lost on container restart or scale-out event.
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    // cz-java-0069: Removed HttpSession parameter. Guest name is now retrieved
    // from the Redis-backed Spring Session store using the session ID from the request header,
    // ensuring consistent reads across all EKS pod instances behind the AWS ALB.
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Auth-Token", required = false) String sessionId) {

        // cz-java-0069: Reading business state from Amazon ElastiCache (Redis) via
        // Spring Session — consistent across all container replicas in the EKS cluster.
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            Session session = sessionRepository.findById(sessionId);
            if (session != null) {
                lastGuest = (String) session.getAttribute("guestName");
            }
        }

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
        // cz-java-0057: Replaced hardcoded absolute file path with environment variable.
        // REPORT_BASE_PATH is injected via Kubernetes ConfigMap / EKS Pod env spec.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
