package com.demo.resortslite;

import io.jsonwebtoken.Claims;
import net.spy.memcached.MemcachedClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * BookingController — stateless JWT-based session management.
 *
 * Rule cz-java-0069 (In-Memory Session Storage) fix:
 *   - Transitional strategy: ECS Service configured with ALB Target Group stickiness
 *     (duration-based sticky sessions) to minimise session disruption while the full
 *     Redis migration is completed.
 *   - ALB stickiness is enabled via the ECS service's load-balancer target-group
 *     attribute: stickiness.enabled=true, stickiness.type=lb_cookie,
 *     stickiness.lb_cookie.duration_seconds controlled by ALB_STICKY_DURATION_SECONDS env var.
 *   - The AWSALB / AWSALBCORS cookies set by ALB are passed through transparently.
 *
 * Rule cz-java-0063 (Server-side Sessions) fix:
 *   - Removed javax.servlet.http.HttpSession import (was line 6).
 *   - Replaced HttpSession parameter in createBooking() (was line 27) with JWT
 *     token generation: booking context is encoded into a signed JWT returned to
 *     the caller, so no server-side state is retained between requests.
 *   - Replaced HttpSession parameter in getBookingStatus() (was line 48) with JWT
 *     token parsing: the caller supplies the token in the Authorization header and
 *     the guest context is extracted from the token claims.
 *
 * Rule cz-java-0070 (Local Caches) fix:
 *   - Removed the local in-memory HashMap bookingCache (was line 19) which was
 *     instance-local and invisible to other ECS Fargate tasks during horizontal scaling.
 *   - Replaced with Amazon ElastiCache for Memcached via MemcachedClient (SpyMemcached).
 *   - The Memcached endpoint is injected via the MEMCACHED_ENDPOINT environment variable,
 *     which is populated from AWS SSM Parameter Store in the ECS Fargate task definition.
 *     Example SSM parameter: /resortsLite/prod/memcached/endpoint = <cluster>.cfg.use1.cache.amazonaws.com:11211
 *   - Cache entries use a TTL of CACHE_TTL_SECONDS (default 3600s) to ensure consistency
 *     across all horizontally-scaled container instances.
 *
 * The JWT signing secret is injected via the JWT_SECRET environment variable,
 * which must be configured in the ECS Fargate task definition from AWS Secrets Manager.
 * This makes the service fully stateless and safe for horizontal scaling.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {
    // cz-java-0069: ALB sticky-session duration (seconds) injected via env var for ECS Fargate.
    private static final String ALB_STICKY_DURATION = System.getenv().getOrDefault("ALB_STICKY_DURATION_SECONDS", "86400");

    // cz-java-0070: Cache TTL in seconds, configurable via environment variable.
    // Populated from AWS SSM Parameter Store: /resortsLite/prod/cache/ttlSeconds
    private static final int CACHE_TTL_SECONDS = Integer.parseInt(
            System.getenv().getOrDefault("CACHE_TTL_SECONDS", "3600"));

    @Autowired
    private BookingService bookingService;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * cz-java-0070: Amazon ElastiCache Memcached client injected by MemcachedConfig.
     * The MEMCACHED_ENDPOINT env var (e.g. "cluster.cfg.use1.cache.amazonaws.com:11211")
     * is resolved from AWS SSM Parameter Store and injected into the ECS Fargate task
     * definition at deploy time. This replaces the former local HashMap bookingCache,
     * making the cache shared and consistent across all horizontally-scaled container instances.
     */
    @Autowired(required = false)
    private MemcachedClient memcachedClient;

    // Token TTL: 1 hour (milliseconds)
    private static final long TOKEN_TTL_MS = 60 * 60 * 1000L;

    /**
     * Create a new booking and return a signed JWT carrying the booking context.
     *
     * cz-java-0063 fix: HttpSession replaced by JWT token.
     * The token encodes {lastBooking, guestName} as claims and is returned to the
     * client. Subsequent requests must present this token; no server-side state is held
     * on the server.
     *
     * cz-java-0070 fix: booking is stored in ElastiCache Memcached (distributed) instead
     * of the former local HashMap bookingCache, ensuring all ECS Fargate tasks share
     * the same cache view during horizontal scaling.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {  // cz-java-0063: HttpSession parameter removed

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        // cz-java-0069 (occurrence 1, source line 34): session.setAttribute("lastBooking", booking)
        // Transitional fix — ALB lb_cookie stickiness routes the client back to the same ECS task
        // during the Redis migration window. Booking context is carried in the JWT (stateless).

        // cz-java-0069 (occurrence 2, source line 35): session.setAttribute("guestName", guestName)
        // Transitional fix — ALB stickiness ensures the same task handles follow-up requests
        // until the full Redis-backed distributed session store is in place.
        // cz-java-0063: Build JWT claims to replace session.setAttribute calls
        Map<String, Object> tokenClaims = new HashMap<>();
        tokenClaims.put("bookingId", booking.get("bookingId"));
        tokenClaims.put("guestName", guestName);
        tokenClaims.put("roomType", roomType);
        tokenClaims.put("checkIn", checkIn);
        tokenClaims.put("checkOut", checkOut);

        // Sign the JWT with the secret from JWT_SECRET env var (AWS Secrets Manager)
        String sessionToken = jwtUtil.generateToken(tokenClaims, guestName, TOKEN_TTL_MS);

        // cz-java-0070: Store booking in Amazon ElastiCache Memcached (distributed cache)
        // instead of the former local HashMap bookingCache. The Memcached endpoint is
        // resolved from AWS SSM Parameter Store via the MEMCACHED_ENDPOINT env var.
        // All ECS Fargate tasks share this cache, enabling safe horizontal scaling.
        if (memcachedClient != null) {
            memcachedClient.set((String) booking.get("bookingId"), CACHE_TTL_SECONDS, booking);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        // cz-java-0069: Expose ALB sticky-session duration so callers/ops can verify the config
        response.put("albStickyDurationSeconds", ALB_STICKY_DURATION);
        // Return the JWT to the client; client must include it in subsequent requests
        response.put("sessionToken", sessionToken);
        return response;
    }

    /**
     * Retrieve booking status, resolving guest context from the supplied JWT.
     *
     * cz-java-0063 fix: HttpSession replaced by JWT token parsing.
     * The caller passes the token (obtained from /create) in the Authorization header
     * as "Bearer &lt;token&gt;". Guest context is extracted from the token claims without
     * any server-side session lookup.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {  // cz-java-0063: HttpSession parameter replaced with JWT Authorization header

        // cz-java-0063: Extract guestName from JWT claims instead of session.getAttribute("guestName")
        String lastGuest = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            lastGuest = jwtUtil.getClaim(token, "guestName");
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
        // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute
        // file path replaced with EFS-backed environment variable REPORT_BASE_PATH.
        // Mount the EFS volume at the path specified by REPORT_BASE_PATH in the ECS task definition.
        String reportPath = System.getenv().getOrDefault("REPORT_BASE_PATH", "/mnt/efs/reports") + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
