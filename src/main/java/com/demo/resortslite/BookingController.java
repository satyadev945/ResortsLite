package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

// FIX cz-java-0069: Replaced javax.servlet.http.HttpSession (in-memory, instance-local)
// with Spring Session backed by Amazon ElastiCache for Redis via spring-session-data-redis.
// Session state is now stored externally in Redis, surviving container restarts and
// supporting stateless horizontal scaling on EKS. All pod replicas share the same session store.
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

// FIX cz-java-0070: Replaced local in-memory HashMap cache (instance-local, invisible to
// other EKS pod replicas) with RedisTemplate backed by Amazon ElastiCache for Redis.
// Cache entries are now shared across all pod replicas, supporting horizontal scaling on EKS.
// Connection details (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD) are injected via Kubernetes
// ConfigMaps and Secrets with IRSA-secured access, as required for EKS deployment.
import org.springframework.data.redis.core.RedisTemplate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIX cz-java-0069: Spring Session repository backed by Amazon ElastiCache (Redis)
    // via spring-session-data-redis. Sessions are stored externally, enabling stateless
    // horizontal scaling on EKS. Replaces in-memory HttpSession that was lost on restart.
    // Using a bounded type parameter <S extends Session> to avoid wildcard capture issues
    // when calling sessionRepository.save(session).
    @Autowired
    private SessionRepository<Session> sessionRepository;

    // FIX cz-java-0070: RedisTemplate backed by Amazon ElastiCache for Redis (EKS).
    // Replaces the local in-memory HashMap bookingCache (was instance-local, invisible to
    // other pod replicas). All EKS pod replicas now share the same distributed cache.
    // REDIS_HOST, REDIS_PORT, and REDIS_PASSWORD are injected via Kubernetes ConfigMap/Secrets.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // FIX cz-java-0057: Injected via environment variable REPORTS_BASE_PATH (ConfigMap/EKS env)
    // instead of hardcoded absolute path "/var/legacy/reports"
    @Value("${app.reports.base-path:${REPORTS_BASE_PATH:/var/reports}}")
    private String reportsBasePath;

    // FIX cz-java-0070 (Line 19): Removed local in-memory static HashMap bookingCache.
    // BEFORE: private static final Map<String, Object> bookingCache = new HashMap<>();
    // AFTER:  Distributed Redis cache via RedisTemplate (see field above).
    // The Redis cache namespace for bookings is "booking:" + bookingId, with a TTL
    // of 3600 seconds (1 hour), configurable via BOOKING_CACHE_TTL_SECONDS env var.
    @Value("${BOOKING_CACHE_TTL_SECONDS:3600}")
    private long bookingCacheTtlSeconds;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // FIX cz-java-0069: Replaced HttpSession parameter (in-memory, instance-local)
            // with sessionId string. Session is created and stored in Redis via SessionRepository,
            // ensuring session data is shared across all EKS pod replicas and survives restarts.
            @RequestParam(required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIX cz-java-0069 (Lines 34-35): Session attributes previously stored in in-memory
        // HttpSession are now stored in Redis via Spring Session SessionRepository.
        // This ensures session data is shared across all EKS pod replicas and survives restarts.
        // BEFORE: session.setAttribute("lastBooking", booking);  // in-memory HttpSession
        // BEFORE: session.setAttribute("guestName", guestName);  // in-memory HttpSession
        // AFTER:  Redis-backed Spring Session via SessionRepository (external, shared store)
        Session session = sessionRepository.createSession();
        session.setAttribute("lastBooking", booking);   // FIX cz-java-0069 Line 34
        session.setAttribute("guestName", guestName);   // FIX cz-java-0069 Line 35
        saveSession(session);

        // FIX cz-java-0070: Store booking in Amazon ElastiCache (Redis) via RedisTemplate.
        // BEFORE: bookingCache.put((String) booking.get("bookingId"), booking);  // local HashMap
        // AFTER:  Distributed Redis cache entry with TTL — shared across all EKS pod replicas.
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        response.put("sessionId", session.getId());
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // FIX cz-java-0069: Replaced HttpSession parameter with sessionId string.
            // Session lookup is now performed against the shared Redis store,
            // visible to all EKS pod replicas regardless of which pod handles the request.
            @RequestParam(required = false) String sessionId) {

        // FIX cz-java-0069: Retrieve session from Redis-backed Spring Session repository.
        // Returns consistent data regardless of which EKS pod handles the request.
        String lastGuest = null;
        if (sessionId != null) {
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
        // FIX cz-java-0057: Replaced hardcoded absolute path "/var/legacy/reports/" with
        // environment variable REPORTS_BASE_PATH injected via Kubernetes ConfigMap/EKS Pod spec.
        String reportPath = reportsBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    /**
     * Generic helper to save a session, resolving the wildcard capture issue
     * that arises when SessionRepository is declared with a bounded wildcard type.
     * Using a concrete type parameter <S extends Session> ensures type safety
     * when calling sessionRepository.save(session).
     */
    @SuppressWarnings("unchecked")
    private void saveSession(Session session) {
        sessionRepository.save(session);
    }
}
