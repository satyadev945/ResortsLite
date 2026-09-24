package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController – cloud-native, stateless REST controller.
 *
 * cr-java-0065 REMEDIATED: All HTTP session state (lastBooking, guestName) has been
 * migrated from in-memory HttpSession to Azure Cache for Redis via Spring Data Redis
 * (RedisTemplate).  Session data is now stored externally, enabling stateless horizontal
 * scaling across multiple Azure App Service / Container Apps instances without server
 * affinity.  The javax.servlet.http.HttpSession import and all session.setAttribute /
 * session.getAttribute calls have been removed and replaced with RedisTemplate operations
 * keyed on the bookingId / guestName so that any instance can retrieve the same data.
 *
 * cr-java-0067 REMEDIATED: The static in-memory bookingCache (HashMap without TTL) has
 * been removed and replaced with Azure Cache for Redis via RedisTemplate with an explicit
 * TTL (configurable via BOOKING_CACHE_TTL_MINUTES, defaulting to 60 minutes).  This
 * eliminates indefinite memory growth, prevents out-of-memory errors, and ensures cache
 * consistency across all application instances in the cluster.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0065 / cr-java-0067 fix: RedisTemplate replaces both HttpSession and the
     * static in-memory HashMap for distributed, TTL-bounded caching.
     * Spring Data Redis serialises values to the Azure Cache for Redis instance configured
     * via spring.redis.host / spring.redis.port / spring.redis.password in
     * application.properties (backed by environment variables REDIS_HOST, REDIS_PORT,
     * REDIS_PASSWORD).
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cr-java-0067 REMEDIATED: TTL for booking cache entries, injected from environment.
    // Set BOOKING_CACHE_TTL_MINUTES in Azure App Service application settings or Container
    // Apps environment variables to control cache expiry.  Defaults to 60 minutes.
    @Value("${app.booking.cache.ttl-minutes:${BOOKING_CACHE_TTL_MINUTES:60}}")
    private long bookingCacheTtlMinutes;

    // cr-java-0071 fix: Hard-coded inventory service URL externalized to Azure App Configuration.
    // Value is injected from application.properties (app.inventory.url) which reads from the
    // INVENTORY_SERVICE_URL environment variable, enabling environment-agnostic deployments.
    @Value("${app.inventory.url:${INVENTORY_SERVICE_URL:http://inventory-service.internal:8081/rooms/available}}")
    private String inventoryServiceUrl;

    // cr-java-0067 REMEDIATED: The static in-memory bookingCache (HashMap without TTL) has
    // been removed.  All cache reads/writes now go through RedisTemplate with an explicit TTL
    // (bookingCacheTtlMinutes) so that entries expire automatically, preventing memory
    // exhaustion and stale data across multiple Azure App Service / Container Apps instances.

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 REMEDIATED: Booking state is now stored in Azure Cache for Redis
        // instead of the in-memory HttpSession.  RedisTemplate writes are visible to every
        // application instance in the cluster, eliminating server affinity requirements.
        //
        // cr-java-0067 REMEDIATED: Cache entries are written with an explicit TTL
        // (bookingCacheTtlMinutes) so they expire automatically, preventing indefinite
        // memory growth and stale data inconsistencies across instances.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set(
                "booking:lastBooking:" + bookingId, booking,
                bookingCacheTtlMinutes, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(
                "booking:guestName:" + bookingId, guestName,
                bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // cr-java-0065 REMEDIATED: Guest name is now retrieved from Azure Cache for Redis
        // instead of the in-memory HttpSession.  Any instance in the cluster can serve this
        // request because the data lives in the shared Redis store, not in local JVM memory.
        String lastGuest = (String) redisTemplate.opsForValue().get("booking:guestName:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 fix: URL is no longer hard-coded. It is injected via @Value from
        // app.inventory.url in application.properties, which is backed by the
        // INVENTORY_SERVICE_URL environment variable / Azure App Configuration.
        String inventoryUrl = inventoryServiceUrl;

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
