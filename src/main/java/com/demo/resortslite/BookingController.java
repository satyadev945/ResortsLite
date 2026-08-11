package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-ready, stateless REST controller.
 *
 * cr-java-0065 fix: All HTTP session state (lastBooking, guestName) has been
 * migrated from in-memory HttpSession to Azure Cache for Redis via
 * Spring Data Redis (StringRedisTemplate).  This removes server affinity,
 * allows horizontal scaling across multiple Azure App Service / Container Apps
 * instances, and survives instance restarts / failover without data loss.
 *
 * Key changes:
 *   - Removed javax.servlet.http.HttpSession import and all HttpSession parameters.
 *   - Injected StringRedisTemplate to read/write session-scoped data in Redis.
 *   - Session keys are namespaced with "session:" + bookingId / guestName to
 *     avoid collisions with other application keys.
 *   - A guestName lookup key is stored as "session:guest:<bookingId>" so that
 *     any instance can retrieve it for the /status endpoint.
 *
 * cr-java-0067 fix: Removed static in-memory bookingCache (HashMap without TTL).
 *   - Replaced with Azure Cache for Redis via StringRedisTemplate with explicit TTL.
 *   - Cache entries are stored under "cache:booking:<bookingId>" with a configurable
 *     TTL (default 30 minutes, overridable via BOOKING_CACHE_TTL_MINUTES env var).
 *   - This eliminates indefinite memory growth, prevents OOM errors, and ensures
 *     cache consistency across all application instances in the Azure cloud environment.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0065 fix: StringRedisTemplate replaces HttpSession for distributed
    // session state storage backed by Azure Cache for Redis.
    @Autowired
    private StringRedisTemplate redisTemplate;

    // cr-java-0067 fix: Static in-memory bookingCache (HashMap without TTL) removed.
    // Booking cache entries are now stored in Azure Cache for Redis with an explicit TTL
    // (BOOKING_CACHE_TTL_MINUTES, default 30 min) via StringRedisTemplate, preventing
    // indefinite memory growth, OOM errors, and stale data across multiple instances.
    @Value("${app.booking.cache-ttl-minutes:30}")
    private long bookingCacheTtlMinutes;

    // cr-java-0071 fix: Hard-coded inventory service URL replaced with externalized configuration
    // loaded from Azure App Configuration / application.properties via @Value injection.
    // was: String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";
    @Value("${app.inventory.available-url}")
    private String inventoryAvailableUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 fix: Session state is now stored in Azure Cache for Redis instead of
        // the in-memory HttpSession.  Any application instance can read these values,
        // enabling stateless horizontal scaling and eliminating server affinity.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set("session:lastBooking:" + bookingId, booking.toString());
        redisTemplate.opsForValue().set("session:guest:" + bookingId, guestName);

        // cr-java-0067 fix: Booking data is cached in Azure Cache for Redis with an explicit TTL
        // instead of the former static in-memory HashMap (bookingCache).  The TTL prevents
        // indefinite memory growth and stale data, and the distributed Redis store ensures
        // cache consistency across all horizontally-scaled application instances.
        redisTemplate.opsForValue().set(
                "cache:booking:" + bookingId,
                booking.toString(),
                bookingCacheTtlMinutes,
                TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // cr-java-0065 fix: Guest name is now retrieved from Azure Cache for Redis instead of
        // the in-memory HttpSession.  This works correctly across all instances in the cluster.
        String lastGuest = redisTemplate.opsForValue().get("session:guest:" + bookingId);

        // cr-java-0067 fix: Booking details are retrieved from the Redis cache (with TTL)
        // instead of the former static in-memory HashMap.
        String cachedBooking = redisTemplate.opsForValue().get("cache:booking:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("cachedBooking", cachedBooking);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 fix: Hard-coded environment URL replaced with value injected from
        // Azure App Configuration / application.properties (app.inventory.available-url).
        // The URL is now environment-agnostic and can be overridden per deployment environment
        // without any code changes, satisfying 12-factor app principle III (Config).
        String inventoryUrl = inventoryAvailableUrl; // cr-java-0071

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
