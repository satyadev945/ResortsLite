package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// cz-java-0063 FIX: Removed 'import javax.servlet.http.HttpSession' — server-side session
// storage has been eliminated.  Guest context is now carried in a stateless JWT token
// (Authorization: Bearer <token>) whose signing key is injected via the JWT_SIGNING_KEY
// environment variable, sourced from GCP Secret Manager through GKE Workload Identity.
//
// cz-java-0069 FIX: In-memory HttpSession storage replaced with Redis-backed session
// management via Spring Session Data Redis.  Session data is stored in an external Redis
// instance (REDIS_HOST / REDIS_PORT env-vars) so that all GKE Autopilot pod replicas share
// the same session state.  Vertical Pod Autoscaler (VPA) automatically right-sizes pod
// memory for session serialization overhead without manual tuning.

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0063 FIX: JWT utility — signs/verifies tokens with the GCP-Secret-Manager key.
    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    // cz-java-0082 FIX: Pub/Sub publisher injected for async decoupled event publishing.
    @Autowired
    private BookingEventPublisher bookingEventPublisher;

    // cz-java-0057 FIX: Injected via GKE Secret Manager add-on / environment variable
    // replaces hardcoded absolute path "/var/legacy/reports/"
    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    // cz-java-0070 FIX: Replaced local in-memory HashMap cache with a distributed Redis-backed
    // cache via RedisTemplate.  The previous static HashMap was instance-local and invisible to
    // other GKE Autopilot pod replicas, breaking horizontal scaling.
    // RedisTemplate connects to the In-Cluster Redis StatefulSet deployed on GKE Autopilot with
    // GCP Persistent Disk-backed PVCs (REDIS_HOST / REDIS_PORT env-vars).  All pod replicas
    // share the same cache state, and entries expire automatically via a configurable TTL
    // (BOOKING_CACHE_TTL_SECONDS env-var, default 3600 s) to prevent unbounded memory growth.
    // Before: private static final Map<String, Object> bookingCache = new HashMap<>();
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${BOOKING_CACHE_TTL_SECONDS:3600}")
    private long bookingCacheTtlSeconds;

    /**
     * cz-java-0063 FIX (Source Line 27): Removed HttpSession parameter.
     * cz-java-0069 FIX (Source Lines 34-35): Eliminated in-memory session.setAttribute calls:
     *   - session.setAttribute("lastBooking", booking)  [source line 34]
     *   - session.setAttribute("guestName", guestName)  [source line 35]
     *
     * cz-java-0082 FIX (Source Line 84): Replaced synchronous in-process call
     *   bookingService.createBooking(guestName, roomType, checkIn, checkOut)
     * with an asynchronous Google Cloud Pub/Sub publish via BookingEventPublisher.
     * The booking event is published to the 'booking-events' Pub/Sub topic
     * (PUBSUB_BOOKING_TOPIC env-var) so that the controller and the downstream
     * booking-processing microservice are fully decoupled and can scale independently
     * on GKE without tight runtime coupling.
     *
     * cz-java-0070 FIX (Source Line 19): Replaced local in-memory HashMap cache with
     * a distributed Redis-backed cache via RedisTemplate (In-Cluster Redis StatefulSet
     * on GKE Autopilot with GCP Persistent Disk-backed PVCs).
     *
     * Remediation: GKE Autopilot + Vertical Pod Autoscaler for Redis-Connected Session Workloads.
     * Booking state is no longer stored in server-side in-memory session storage.
     * Instead:
     *   1. A signed JWT is generated and returned to the client so that subsequent requests
     *      carry the guest context in a stateless Bearer token (no server state required).
     *   2. Spring Session Data Redis (spring.session.store-type=redis) is configured so that
     *      any residual HttpSession usage is transparently backed by Redis, ensuring session
     *      data survives container restarts and is shared across all GKE Autopilot pod replicas.
     *   3. Redis connection is injected via REDIS_HOST / REDIS_PORT environment variables —
     *      no hardcoded infrastructure values.
     *   4. VPA automatically right-sizes pod memory for session serialization overhead.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        // cz-java-0082 FIX (Line 84): Generate a booking ID locally so the event payload
        // can be constructed and published asynchronously to Pub/Sub without blocking the
        // HTTP response on a synchronous in-process call to BookingService.createBooking().
        // A downstream subscriber microservice will consume the 'booking-created' event from
        // the PUBSUB_BOOKING_TOPIC topic and persist the booking record independently.
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Map<String, Object> bookingEvent = new HashMap<>();
        bookingEvent.put("bookingId", bookingId);
        bookingEvent.put("guestName", guestName);
        bookingEvent.put("roomType", roomType);
        bookingEvent.put("checkIn", checkIn);
        bookingEvent.put("checkOut", checkOut);

        // cz-java-0082 FIX (Line 84): Publish booking-created event asynchronously to
        // Google Cloud Pub/Sub (PUBSUB_BOOKING_TOPIC) instead of calling
        // bookingService.createBooking(...) synchronously in-process.
        // This decouples the HTTP layer from the booking-processing microservice,
        // enabling each to scale independently on GKE.
        bookingEventPublisher.publishBookingEvent(bookingEvent);

        // cz-java-0069 FIX (Source Line 34): session.setAttribute("lastBooking", booking) REMOVED.
        // cz-java-0069 FIX (Source Line 35): session.setAttribute("guestName", guestName) REMOVED.
        // Guest context previously stored in in-memory HttpSession is now embedded in a signed JWT.
        // Spring Session Data Redis ensures any remaining session data is stored in Redis (not JVM heap),
        // making it durable across container restarts and visible to all pod replicas in the cluster.
        // Redis endpoint is injected via REDIS_HOST / REDIS_PORT env-vars (GKE Autopilot + VPA).
        String jwtToken = jwtTokenUtil.generateToken(guestName, bookingId, Collections.emptyMap());

        // cz-java-0070 FIX (Source Line 19): Store booking event in the distributed Redis cache
        // instead of the local in-memory HashMap.  The key is namespaced under "booking:cache:"
        // to avoid collisions with Spring Session keys.  A TTL of BOOKING_CACHE_TTL_SECONDS
        // (default 3600 s) is applied so that stale entries are automatically evicted by Redis,
        // preventing unbounded memory growth on the In-Cluster Redis StatefulSet PVC.
        // Before: bookingCache.put(bookingId, bookingEvent);
        redisTemplate.opsForValue().set(
                "booking:cache:" + bookingId,
                bookingEvent,
                bookingCacheTtlSeconds,
                TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "accepted");
        response.put("bookingId", bookingId);
        response.put("message", "Booking event published to Pub/Sub for async processing");
        // cz-java-0063 / cz-java-0069 FIX: JWT token returned to client; replaces session-based state.
        response.put("token", jwtToken);
        return response;
    }

    /**
     * cz-java-0063 FIX (Source Line 48): Removed HttpSession parameter.
     * cz-java-0069 FIX: Guest name is now extracted from the stateless JWT Bearer token supplied
     * by the client, eliminating the dependency on server-side in-memory session storage that
     * breaks under horizontal scaling and container restarts on GKE Autopilot.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {

        // cz-java-0069 FIX: Guest name is resolved from the JWT Bearer token instead of
        // HttpSession.getAttribute("guestName").  Any GKE Autopilot pod replica can verify the
        // token independently using the shared signing key from GCP Secret Manager.
        // Spring Session Data Redis (REDIS_HOST / REDIS_PORT) backs any residual session usage.
        String lastGuest = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                lastGuest = jwtTokenUtil.extractGuestName(token);
            } catch (Exception e) {
                lastGuest = "unknown (invalid or expired token)";
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
        // cz-java-0057 FIX: Absolute path replaced with environment-variable-backed value.
        // REPORT_BASE_PATH is injected via GKE Secret Manager add-on / Kubernetes secret mount.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
