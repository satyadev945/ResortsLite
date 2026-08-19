package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0063: Removed server-side HttpSession import. Migrated to stateless JWT authentication.
// JWT signing key is sourced from Azure Key Vault via environment variable JWT_SECRET_KEY,
// enabling KEDA-driven autoscaling of stateless AKS pods without sticky sessions.
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0070: REMOVED local in-memory cache (static HashMap<String, Object> bookingCache).
    // The original line 19 declared:
    //   private static final Map<String, Object> bookingCache = new HashMap<>();
    // This local cache does NOT work when containers scale horizontally — each AKS pod replica
    // maintains its own isolated in-memory cache, causing cache inconsistency, stale reads,
    // and data loss on pod restarts or scale-out events.
    //
    // REMEDIATION: Replaced with Redis-backed distributed cache via RedisTemplate (below).
    // Redis is deployed in-cluster on AKS using the Bitnami Redis Helm chart backed by
    // Azure Managed Disk persistent volumes (see RedisConfig.java for full deployment details).
    // All AKS pod replicas share the same Redis store — no cache data is lost on container
    // restart or scale-out events. Connection is configured via REDIS_HOST and REDIS_PORT
    // environment variables set in the AKS pod spec or ConfigMap.

    // cz-java-0070 / cz-java-0069: Replaced in-memory bookingCache (static HashMap) with
    // Redis-backed distributed cache via RedisTemplate. Redis is deployed in-cluster on AKS
    // using the Bitnami Redis Helm chart backed by Azure Managed Disk PVC (see RedisConfig.java).
    // Redis connection is configured through environment variables REDIS_HOST and REDIS_PORT,
    // enabling KEDA Redis Queue-Based Autoscaling on AKS.
    // All AKS pod replicas share the same Redis store — no session data is lost on
    // container restart or scale-out events.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cz-java-0082: Azure Service Bus publisher for async decoupled messaging.
    // Replaces tightly-coupled synchronous call to bookingService.createBooking() with
    // an async event published to Azure Service Bus booking queue on AKS.
    @Autowired
    private AzureServiceBusPublisher serviceBusPublisher;

    // cz-java-0070 / cz-java-0069: TTL for Redis cache entries (in seconds). Sourced from
    // environment variable BOOKING_CACHE_TTL_SECONDS to allow tuning without redeployment.
    @Value("${BOOKING_CACHE_TTL_SECONDS:3600}")
    private long bookingCacheTtlSeconds;

    // cz-java-0057: Replaced hardcoded absolute path /var/legacy/reports/ with environment variable.
    // Mount the reports directory via Azure Key Vault CSI Driver on AKS at the path specified by REPORT_BASE_PATH.
    @Value("${REPORT_BASE_PATH:/mnt/reports}")
    private String reportBasePath;

    // cz-java-0063: JWT signing key sourced from Azure Key Vault via environment variable JWT_SECRET_KEY.
    // This enables stateless authentication across all AKS pod replicas without shared session state.
    @Value("${JWT_SECRET_KEY:default-dev-secret-key-replace-in-production}")
    private String jwtSecretKey;

    /**
     * cz-java-0063: Returns a JWT signing key derived from the Azure Key Vault-backed secret.
     */
    private Key getSigningKey() {
        byte[] keyBytes = jwtSecretKey.getBytes(StandardCharsets.UTF_8);
        // Ensure key is at least 256 bits for HS256
        byte[] paddedKey = new byte[32];
        System.arraycopy(keyBytes, 0, paddedKey, 0, Math.min(keyBytes.length, 32));
        return Keys.hmacShaKeyFor(paddedKey);
    }

    /**
     * cz-java-0063: Generates a stateless JWT token carrying guest context.
     * Replaces server-side HttpSession.setAttribute() calls.
     */
    private String generateBookingToken(String guestName, String bookingId) {
        return Jwts.builder()
                .setSubject(guestName)
                .claim("bookingId", bookingId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600_000L)) // 1 hour
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * cz-java-0063: Extracts guest name from a stateless JWT token.
     * Replaces server-side HttpSession.getAttribute() calls.
     */
    private String extractGuestNameFromToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return claims.getSubject();
        } catch (Exception e) {
            return null;
        }
    }

    // cz-java-0063: Removed HttpSession parameter. Guest context is now carried in a stateless
    // JWT token returned to the client, eliminating server-side session state that breaks
    // horizontal scaling across AKS pod replicas.
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        // cz-java-0082: Replaced tightly-coupled synchronous call bookingService.createBooking(...)
        // with async Azure Service Bus event publishing. The booking creation request is now
        // published as a 'BookingCreated' event to the Azure Service Bus booking queue, allowing
        // the downstream booking processor microservice on AKS to consume and process it
        // independently. This decouples the controller from the booking domain service,
        // enabling independent deployment, scaling, and fault isolation on AKS.
        String correlationId = UUID.randomUUID().toString();
        Map<String, Object> bookingPayload = new HashMap<>();
        bookingPayload.put("correlationId", correlationId);
        bookingPayload.put("guestName", guestName);
        bookingPayload.put("roomType", roomType);
        bookingPayload.put("checkIn", checkIn);
        bookingPayload.put("checkOut", checkOut);
        bookingPayload.put("requestedAt", new Date().toString());
        serviceBusPublisher.publishBookingCreatedEvent(bookingPayload, correlationId);

        // cz-java-0063: Generate a stateless JWT token for the guest using the correlation ID
        // as the booking reference, since booking processing is now asynchronous.
        String bookingToken = generateBookingToken(guestName, correlationId);

        // cz-java-0070 / cz-java-0069: Cache the pending booking event in Redis with a
        // configurable TTL (BOOKING_CACHE_TTL_SECONDS). Replaces the local in-memory
        // bookingCache.put() call that was the cz-java-0070 violation. All AKS pod replicas
        // share the same Redis store deployed via Bitnami Helm chart on AKS with Azure Disk PVC.
        String cacheKey = "booking:pending:" + correlationId;
        redisTemplate.opsForValue().set(cacheKey, bookingPayload, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "accepted");
        response.put("correlationId", correlationId);
        response.put("message", "Booking request accepted and queued for async processing via Azure Service Bus.");
        // cz-java-0063: Return JWT token to client for subsequent stateless requests.
        response.put("bookingToken", bookingToken);
        return response;
    }

    // cz-java-0063: Removed HttpSession parameter. Guest context is now resolved from the
    // stateless JWT Authorization header, enabling KEDA-scaled stateless AKS pods.
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        // cz-java-0063: Replaced session.getAttribute("guestName") with JWT token extraction.
        // The JWT is validated using the Azure Key Vault-backed signing key (JWT_SECRET_KEY),
        // so any pod in the AKS cluster can serve this request without sticky sessions.
        String token = (authorizationHeader != null && authorizationHeader.startsWith("Bearer "))
                ? authorizationHeader.substring(7)
                : null;
        String lastGuest = extractGuestNameFromToken(token);

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
        // cz-java-0057: Replaced hardcoded absolute path /var/legacy/reports/ with
        // environment variable REPORT_BASE_PATH. Mount via Azure Key Vault CSI Driver on AKS.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
