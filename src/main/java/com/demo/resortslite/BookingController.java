package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// cr-java-0065 FIX: Removed javax.servlet.http.HttpSession import.
// HTTP session state has been replaced with Azure Cache for Redis via Spring Data Redis
// (RedisTemplate). Session attributes are now stored in a distributed Redis store,
// enabling stateless application instances, horizontal scaling, and zero data loss
// during instance termination or load-balancer failover.

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 FIX: Removed static in-memory HashMap cache (bookingCache).
    // The instance-local, TTL-less HashMap has been replaced with Azure Cache for Redis
    // via RedisTemplate. Cache entries are now stored in a distributed Redis store with
    // a configurable TTL (default: 60 minutes), preventing indefinite memory growth,
    // stale data inconsistencies, and cache invisibility across multiple instances.
    // The TTL value is externalised via the 'app.cache.booking.ttl-minutes' property
    // (backed by the APP_CACHE_BOOKING_TTL_MINUTES environment variable) so it can be
    // tuned per environment without code changes.
    @Value("${app.cache.booking.ttl-minutes:60}")
    private long bookingCacheTtlMinutes;

    // cr-java-0071 FIX: Hard-coded inventory service URL replaced with a value injected
    // from Azure App Configuration via the 'app.inventory.url' property key.
    // The property is externalised in application.properties and resolved at runtime from
    // the APP_INVENTORY_URL environment variable, enabling environment-agnostic deployments.
    @Value("${app.inventory.url}")
    private String inventoryServiceUrl;

    // cr-java-0065 FIX: RedisTemplate replaces HttpSession for distributed session state.
    // All session attributes (lastBooking, guestName) are stored in Azure Cache for Redis
    // under a session-scoped key, making every application instance stateless and
    // allowing the load balancer to route requests to any instance without affinity.
    //
    // cr-java-0067 FIX: RedisTemplate is also used for distributed booking cache with TTL,
    // replacing the removed static in-memory HashMap (bookingCache).
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session attributes previously stored in HttpSession are now
        // persisted in Azure Cache for Redis via RedisTemplate. Using a caller-supplied
        // sessionId as the Redis key prefix ensures the data is accessible from any
        // application instance, eliminating server affinity and enabling auto-scaling.
        String sessionKey = "session:" + sessionId;
        redisTemplate.opsForHash().put(sessionKey, "lastBooking", booking);
        redisTemplate.opsForHash().put(sessionKey, "guestName", guestName);

        // cr-java-0067 FIX: Booking is now cached in Azure Cache for Redis with a TTL
        // instead of the removed static in-memory HashMap. The Redis key is prefixed with
        // "booking:cache:" to avoid collisions with session keys. The TTL (default 60 min)
        // is configurable via the APP_CACHE_BOOKING_TTL_MINUTES environment variable,
        // preventing indefinite memory growth and ensuring cache consistency across all
        // application instances in the cluster.
        String bookingCacheKey = "booking:cache:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(bookingCacheKey, booking, bookingCacheTtlMinutes, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "") String sessionId) {

        // cr-java-0065 FIX: Guest name is now retrieved from Azure Cache for Redis instead
        // of the local HttpSession. Any instance in the cluster can serve this request
        // because the state lives in the shared Redis store, not in instance memory.
        String sessionKey = "session:" + sessionId;
        String lastGuest = (String) redisTemplate.opsForHash().get(sessionKey, "guestName");

        // cr-java-0067 FIX: Booking details are now looked up from the distributed Redis
        // cache (with TTL) before falling back to the BookingService. This replaces the
        // removed instance-local HashMap lookup, ensuring all instances share the same
        // cached view of booking data and that stale entries expire automatically.
        String bookingCacheKey = "booking:cache:" + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(bookingCacheKey);
        Object bookingDetails = (cachedBooking != null) ? cachedBooking : bookingService.getBookingById(bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingDetails);
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX (line 66): The hard-coded URL
        //   "http://inventory-service.internal:8081/rooms/available"
        // has been removed. The URL is now resolved at runtime from the
        // 'app.inventory.url' property, which is backed by the APP_INVENTORY_URL
        // environment variable and can be overridden per environment via
        // Azure App Configuration without any code change.
        String inventoryUrl = inventoryServiceUrl + "/available";

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
        // have their own isolated file systems — /var/legacy/reports/ won't be present.
        String reportPath = "/var/legacy/reports/" + month + "_bookings.pdf"; // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
