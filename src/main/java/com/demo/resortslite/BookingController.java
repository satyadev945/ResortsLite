package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;

import net.spy.memcached.MemcachedClient;
import net.spy.memcached.AddrUtil;
import java.io.IOException;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private static final Logger logger = Logger.getLogger(BookingController.class.getName());

    @Autowired
    private BookingService bookingService;

    // EFS-backed base path injected via environment variable for container portability (cz-java-0057)
    @Value("${REPORT_BASE_PATH:/mnt/efs/reports/}")
    private String reportBasePath;

    // JWT signing secret injected from AWS Secrets Manager via ECS Fargate task environment (cz-java-0063)
    @Value("${JWT_SIGNING_SECRET}")
    private String jwtSigningSecret;

    // cz-java-0070 [Local Caches]: Memcached endpoint injected via AWS SSM Parameter Store /
    // ECS Fargate task environment variable — replaces instance-local HashMap cache.
    // Endpoint format: <elasticache-cluster-endpoint>:11211
    @Value("${MEMCACHED_ENDPOINT:localhost:11211}")
    private String memcachedEndpoint;

    // cz-java-0070 [Local Caches]: Memcached cache TTL (seconds) injected via environment variable.
    // Default: 3600 seconds (1 hour). Set CACHE_TTL_SECONDS in ECS task environment to override.
    @Value("${CACHE_TTL_SECONDS:3600}")
    private int cacheTtlSeconds;

    /**
     * cz-java-0070 [Local Caches]: Returns a MemcachedClient connected to the Amazon ElastiCache
     * for Memcached cluster whose endpoint is provided via the MEMCACHED_ENDPOINT environment
     * variable (injected from AWS SSM Parameter Store into the ECS Fargate task definition).
     * This replaces the previous instance-local HashMap (bookingCache) which was invisible to
     * other Fargate task replicas and broke horizontal scaling.
     */
    private MemcachedClient getMemcachedClient() throws IOException {
        return new MemcachedClient(AddrUtil.getAddresses(memcachedEndpoint));
    }

    // cz-java-0069 [Transitional Strategy]: ALB Target Group Stickiness is enabled on the
    // ECS Fargate service to minimise session disruption while full Redis migration is pending.
    // ALB sticky sessions are configured via ECS Service load balancer target group:
    //   - stickiness.enabled = true
    //   - stickiness.type    = lb_cookie
    //   - stickiness.lb_cookie.duration_seconds = ${ALB_STICKY_SESSION_DURATION_SECONDS:86400}
    // The ALB injects an AWSALB cookie that pins each client to the same Fargate task.
    // This is a transitional measure; full stateless migration via Redis is the target state.

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 [ALB Session Affinity - Transitional]: Session state is preserved
        // across ECS Fargate task restarts by relying on ALB sticky-session cookie (AWSALB).
        // The ALB target group stickiness (lb_cookie, duration=${ALB_STICKY_SESSION_DURATION_SECONDS:86400}s)
        // ensures the same client is routed to the same task until full Redis migration is done.
        // Line 34 (source): session.setAttribute("lastBooking", booking) — replaced by JWT claim below.
        // Line 35 (source): session.setAttribute("guestName", guestName)  — replaced by JWT claim below.
        // Both in-memory HttpSession writes are eliminated; booking state travels in the signed JWT
        // so any Fargate task can validate it without shared server-side session storage.
        // Transitional ALB stickiness config: ALB_STICKY_SESSION_DURATION_SECONDS env var controls TTL.
        String token = Jwts.builder()
                .setSubject(guestName)
                .claim("bookingId", booking.get("bookingId"))
                .claim("guestName", guestName)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 86400000L))
                .signWith(SignatureAlgorithm.HS256, jwtSigningSecret.getBytes())
                .compact();

        // cz-java-0070 [Local Caches]: Store booking in Amazon ElastiCache for Memcached
        // (distributed cache shared across all ECS Fargate task replicas) instead of the
        // previous instance-local HashMap. Endpoint is injected via MEMCACHED_ENDPOINT env var
        // sourced from AWS SSM Parameter Store in the ECS Fargate task definition.
        String bookingId = (String) booking.get("bookingId");
        MemcachedClient memcachedClient = null;
        try {
            memcachedClient = getMemcachedClient();
            memcachedClient.set(bookingId, cacheTtlSeconds, booking);
        } catch (IOException e) {
            logger.warning("cz-java-0070: Could not connect to Memcached at " + memcachedEndpoint
                    + " — booking " + bookingId + " not cached. Error: " + e.getMessage());
        } catch (Exception e) {
            logger.warning("cz-java-0070: Failed to cache booking " + bookingId
                    + " in Memcached. Error: " + e.getMessage());
        } finally {
            if (memcachedClient != null) {
                memcachedClient.shutdown();
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        response.put("token", token);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        // Stateless JWT token validation replaces server-side HttpSession reads (cz-java-0063)
        String lastGuest = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            Claims claims = Jwts.parser()
                    .setSigningKey(jwtSigningSecret.getBytes())
                    .parseClaimsJws(token)
                    .getBody();
            lastGuest = claims.get("guestName", String.class);
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
        // Absolute file path replaced with EFS-backed environment variable for container portability (cz-java-0057)
        String reportPath = reportBasePath + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
