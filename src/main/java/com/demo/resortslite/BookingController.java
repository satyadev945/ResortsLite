package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
// cz-java-0069 FIX (Line 6): Replaced javax.servlet.http.HttpSession with
// Spring Session's SessionRepository-backed session via HttpServletRequest.
// Spring Session transparently stores session data in Amazon ElastiCache (Redis),
// making sessions container-restart-safe and horizontally scalable across EKS pods.
// In-memory HttpSession is lost on container restart or when requests are routed to
// a different pod — externalizing to Redis eliminates this stateful container blocker.
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0069 FIX: Spring Session repository backed by Amazon ElastiCache (Redis)
    // via spring-session-data-redis. All session reads/writes go to Redis, not JVM heap.
    @Autowired
    private SessionRepository sessionRepository;

    // cz-java-0070 FIX: Replaced local in-memory HashMap cache with Amazon ElastiCache (Redis)
    // via RedisTemplate. The previous static HashMap was instance-local and invisible to other
    // EKS pods, breaking horizontal scaling. Redis provides a shared, distributed cache that
    // all container replicas can read and write consistently across the EKS cluster.
    // Connection details are injected via Kubernetes ConfigMap/Secrets (REDIS_HOST, REDIS_PORT).
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Cache TTL in seconds — configurable via environment variable (default: 3600s / 1 hour)
    @Value("${BOOKING_CACHE_TTL_SECONDS:3600}")
    private long bookingCacheTtlSeconds;

    @Value("${REPORT_BASE_PATH:/var/legacy/reports/}")
    private String reportBasePath;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cz-java-0069 FIX (Line 27 in source): Replaced HttpSession parameter with
            // HttpServletRequest so Spring Session can intercept and route all session
            // attribute writes to Amazon ElastiCache (Redis) instead of JVM in-memory storage.
            HttpServletRequest request) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 FIX (Source Lines 34–35): Replaced in-memory HttpSession.setAttribute()
        // calls with Spring Session setAttribute() backed by Amazon ElastiCache (Redis).
        // Before: session.setAttribute("lastBooking", booking);  // lost on container restart
        // Before: session.setAttribute("guestName", guestName);  // lost on container restart
        // After:  springSession.setAttribute(...) persisted to Redis via SessionRepository.save()
        // This ensures session state survives pod restarts and is shared across all EKS replicas.
        Session springSession = getOrCreateSpringSession(request);
        springSession.setAttribute("lastBooking", booking);   // cz-java-0069 fixed — Redis-backed
        springSession.setAttribute("guestName", guestName);   // cz-java-0069 fixed — Redis-backed
        sessionRepository.save(springSession);

        // cz-java-0070 FIX: Store booking in Amazon ElastiCache (Redis) instead of local HashMap.
        // Before: bookingCache.put((String) booking.get("bookingId"), booking); // instance-local
        // After:  Redis SET with TTL — shared across all EKS pods, evicted automatically.
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // cz-java-0069 FIX: Replaced HttpSession parameter with HttpServletRequest.
            // Session attributes are now read from Amazon ElastiCache (Redis) via Spring Session,
            // so any EKS pod in the cluster can serve the request with consistent session data.
            HttpServletRequest request) {

        // cz-java-0069 FIX: Read session attribute from Spring Session (ElastiCache/Redis-backed)
        // Before: String lastGuest = (String) session.getAttribute("guestName"); // in-memory only
        // After:  Reads from Redis — consistent across all container instances in the EKS cluster
        Session springSession = getOrCreateSpringSession(request);
        String lastGuest = springSession.getAttribute("guestName");

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
        // file path replaced with environment variable injection via ConfigMap/EKS.
        String reportPath = reportBasePath + month + "_bookings.pdf"; // cz-java-0057 fixed

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    /**
     * cz-java-0069 FIX: Retrieves an existing Spring Session from Amazon ElastiCache (Redis)
     * using the session ID stored in the request, or creates a new one if none exists.
     * Spring Session's RedisIndexedSessionRepository handles all Redis I/O transparently,
     * ensuring session data is never stored in JVM heap memory.
     */
    @SuppressWarnings("unchecked")
    private Session getOrCreateSpringSession(HttpServletRequest request) {
        String sessionId = request.getRequestedSessionId();
        Session session = null;
        if (sessionId != null) {
            session = sessionRepository.findById(sessionId);
        }
        if (session == null) {
            session = sessionRepository.createSession();
        }
        return session;
    }
}
