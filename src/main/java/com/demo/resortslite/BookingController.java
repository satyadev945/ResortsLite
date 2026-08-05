package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// cr-java-0065 FIX: Removed javax.servlet.http.HttpSession import. HTTP session state has been
// externalized to Azure Cache for Redis via Spring Data Redis (RedisTemplate). Session attributes
// are now stored in a distributed Redis store, enabling stateless application instances,
// horizontal scaling, and zero data loss during auto-scaling or failover events.

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 FIX: Removed instance-local in-memory HashMap cache (no TTL). Booking data
    // is now stored in Azure Cache for Redis via RedisTemplate with an explicit TTL, enabling
    // distributed cache sharing across all application instances and preventing memory exhaustion.
    // The static HashMap has been fully replaced by Redis-backed cache entries below.

    // cr-java-0065 FIX: RedisTemplate replaces HttpSession for distributed session state storage.
    // All session attributes are stored in Azure Cache for Redis, making every application
    // instance stateless and enabling safe horizontal scaling across multiple nodes.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // Session TTL: 30 minutes — matches typical HTTP session timeout
    private static final long SESSION_TTL_MINUTES = 30L;

    // cr-java-0067 FIX: Booking cache TTL — 60 minutes. Entries are automatically evicted from
    // Azure Cache for Redis after this period, preventing unbounded memory growth and ensuring
    // stale booking data does not persist indefinitely across distributed instances.
    private static final long BOOKING_CACHE_TTL_MINUTES = 60L;

    // cr-java-0071 FIX: Hard-coded inventory service URL replaced with value injected from
    // Azure App Configuration / application.properties via @Value. The URL is now environment-
    // agnostic and can be overridden per deployment without any code change.
    @Value("${app.inventory.url}")
    private String inventoryServiceUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Booking state is now stored in Azure Cache for Redis instead of
        // the in-memory HTTP session. RedisTemplate.opsForValue().set() persists the session
        // attributes in the distributed Redis store with a TTL, so any application instance
        // can retrieve the data — eliminating server affinity and enabling horizontal scaling.
        String redisKey = "session:" + sessionId + ":lastBooking";
        String guestKey = "session:" + sessionId + ":guestName";
        redisTemplate.opsForValue().set(redisKey, booking, SESSION_TTL_MINUTES, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set(guestKey, guestName, SESSION_TTL_MINUTES, TimeUnit.MINUTES);

        // cr-java-0067 FIX: Replaced in-memory HashMap cache entry (no TTL) with a Redis-backed
        // cache entry using RedisTemplate with an explicit TTL (BOOKING_CACHE_TTL_MINUTES = 60).
        // The booking is stored in Azure Cache for Redis under a namespaced key, visible to all
        // application instances and automatically evicted after the TTL to prevent unbounded
        // memory growth and stale data inconsistencies across horizontally scaled instances.
        String bookingCacheKey = "cache:booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(bookingCacheKey, booking, BOOKING_CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        // cr-java-0065 FIX: Session state is now retrieved from Azure Cache for Redis instead
        // of the in-memory HTTP session. RedisTemplate.opsForValue().get() fetches the
        // distributed session attribute, returning consistent data regardless of which
        // application instance handles the request — no server affinity required.
        String guestKey = "session:" + sessionId + ":guestName";
        String lastGuest = (String) redisTemplate.opsForValue().get(guestKey);

        // cr-java-0067 FIX: Booking lookup now reads from Azure Cache for Redis instead of
        // the removed instance-local HashMap. Cache misses fall back to the booking service,
        // ensuring correctness even when the cache entry has expired or been evicted.
        String bookingCacheKey = "cache:booking:" + bookingId;
        @SuppressWarnings("unchecked")
        Map<String, Object> cachedBooking = (Map<String, Object>) redisTemplate.opsForValue().get(bookingCacheKey);
        Object bookingDetails = (cachedBooking != null) ? cachedBooking : bookingService.getBookingById(bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingDetails);
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX (Line 66): Hard-coded URL "http://inventory-service.internal:8081/rooms/available"
        // replaced with externalized property injected via @Value("${app.inventory.url}").
        // The URL is now sourced from Azure App Configuration, enabling environment-agnostic
        // deployments without code changes between dev, staging, and production.
        String inventoryUrl = inventoryServiceUrl + "/available"; // cr-java-0071

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
