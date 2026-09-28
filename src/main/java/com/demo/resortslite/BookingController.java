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

/**
 * cr-java-0065 FIX: HTTP session state externalized to Azure Cache for Redis via
 * Spring Session Data Redis. The {@code @EnableRedisHttpSession} annotation replaces
 * the default in-memory HttpSession implementation with a Redis-backed store, making
 * all {@code session.setAttribute} / {@code session.getAttribute} calls transparent
 * to the application while ensuring session data is shared across all instances.
 *
 * This enables stateless, horizontally-scalable deployments on Azure (App Service,
 * Container Apps, AKS) where any instance can serve any request without sticky sessions.
 *
 * Required configuration (application.properties / environment variables):
 *   SPRING_REDIS_HOST  – Azure Cache for Redis hostname
 *   SPRING_REDIS_PORT  – Redis port (default 6380 for Azure TLS)
 *   SPRING_REDIS_PASSWORD – Redis access key (retrieved from Azure Key Vault)
 *   SPRING_REDIS_SSL   – true (Azure Cache for Redis enforces TLS)
 *
 * cr-java-0067 FIX: In-memory HashMap cache replaced with Azure Cache for Redis via
 * Spring Data RedisTemplate. Each booking entry is stored with a configurable TTL
 * (default 60 minutes, overridable via BOOKING_CACHE_TTL_MINUTES env var), preventing
 * indefinite memory growth, stale data inconsistencies, and instance-local cache
 * isolation that breaks horizontal scaling in cloud environments.
 *
 * Cache key prefix: "booking:" + bookingId
 * TTL configuration: app.booking.cache.ttl-minutes (default 60)
 */
@EnableRedisHttpSession
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0067 FIX: RedisTemplate replaces the static in-memory HashMap cache.
     * RedisTemplate is backed by Azure Cache for Redis, providing:
     *   - Distributed cache shared across all application instances
     *   - TTL-based expiration to prevent indefinite memory growth and stale data
     *   - Persistence across instance restarts and rolling deployments
     *   - Consistent cache state regardless of which instance handles the request
     *
     * Auto-configured by Spring Boot via spring-boot-starter-data-redis using the
     * SPRING_REDIS_HOST / SPRING_REDIS_PORT / SPRING_REDIS_PASSWORD / SPRING_REDIS_SSL
     * environment variables pointing to the Azure Cache for Redis instance.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * cr-java-0067 FIX: Cache TTL loaded from externalized configuration.
     * Override via BOOKING_CACHE_TTL_MINUTES environment variable or
     * app.booking.cache.ttl-minutes in Azure App Configuration.
     * Default: 60 minutes — prevents indefinite memory growth and stale data.
     */
    @Value("${app.booking.cache.ttl-minutes:60}")
    private long bookingCacheTtlMinutes;

    /**
     * cr-java-0071 FIX: Hard-coded environment URL removed.
     * The inventory service endpoint is now loaded from Azure App Configuration at runtime
     * via the externalized property key {@code app.inventory.available-endpoint}.
     * Set the environment variable or Azure App Configuration key:
     *   app.inventory.available-endpoint=https://inventory-service.internal:8081/rooms/available
     * This enables environment-agnostic deployments without code changes between
     * dev / staging / production environments.
     */
    @Value("${app.inventory.available-endpoint}")
    private String inventoryAvailableEndpoint;

    /** Redis key prefix for booking cache entries (cr-java-0067 FIX). */
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session state is now stored in Azure Cache for Redis via
        // Spring Session Data Redis (enabled by @EnableRedisHttpSession on this class).
        // The HttpSession API is unchanged; the underlying store is Redis, so session
        // data is visible to every application instance behind the Azure load balancer.
        // Auto-scaling, failover, and rolling deployments all work without sticky sessions.
        session.setAttribute("lastBooking", booking); // backed by Azure Cache for Redis
        session.setAttribute("guestName", guestName); // backed by Azure Cache for Redis

        // cr-java-0067 FIX: Booking entry stored in Azure Cache for Redis via RedisTemplate
        // with a TTL of bookingCacheTtlMinutes (default 60 minutes).
        // This replaces the previous static HashMap which caused:
        //   - Indefinite memory growth (no eviction policy)
        //   - Instance-local cache (invisible to other instances behind the load balancer)
        //   - Stale data inconsistencies across horizontally-scaled deployments
        // The Redis key format is "booking:<bookingId>" to avoid collisions with other
        // cache namespaces sharing the same Azure Cache for Redis instance.
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
            HttpSession session) {

        // cr-java-0065 FIX: Session attribute is now read from Azure Cache for Redis via
        // Spring Session Data Redis. Any instance in the cluster can serve this request
        // and retrieve the correct guestName stored by any other instance.
        String lastGuest = (String) session.getAttribute("guestName"); // backed by Azure Cache for Redis

        // cr-java-0067 FIX: Booking details retrieved from Azure Cache for Redis.
        // If the TTL has expired or the entry was never cached, falls back to the
        // BookingService (database lookup) to ensure correctness.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
        @SuppressWarnings("unchecked")
        Map<String, Object> cachedBooking = (Map<String, Object>) redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        // Use cached value if present (Redis hit); otherwise fall back to service (Redis miss)
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: Hard-coded environment URL replaced with externalized configuration.
        // The inventory service URL is now injected from Azure App Configuration via
        // the @Value("${app.inventory.available-endpoint}") field above.
        // This allows the endpoint to differ per environment (dev/staging/prod) without
        // any code changes, satisfying cloud-native externalized configuration principles.
        String inventoryUrl = inventoryAvailableEndpoint;

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
