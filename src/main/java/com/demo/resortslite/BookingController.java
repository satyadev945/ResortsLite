package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

// cr-java-0065 FIX: @EnableRedisHttpSession transparently replaces the default in-memory
// HttpSession store with Azure Cache for Redis. All session.setAttribute / getAttribute calls
// below now read/write to the shared Redis store, enabling stateless horizontal scaling
// across multiple Azure App Service / Container Apps instances without sticky sessions.
@EnableRedisHttpSession
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0071 FIX: Hardcoded environment URL externalized to Azure App Configuration.
    // Value is injected from application.properties / environment variable
    // AZURE_INVENTORY_SERVICE_URL, enabling environment-agnostic deployments.
    @Value("${app.inventory.service.url}")
    private String inventoryServiceUrl;

    // cr-java-0067 FIX: In-memory HashMap cache replaced with Azure Cache for Redis via
    // Spring Data RedisTemplate. Entries are stored with a configurable TTL
    // (default 30 minutes, overridable via AZURE_BOOKING_CACHE_TTL_MINUTES) to prevent
    // indefinite memory growth, stale data, and cache inconsistency across multiple
    // Azure App Service / Container Apps instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${azure.cache.booking.ttl-minutes:30}")
    private long bookingCacheTtlMinutes;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session is now backed by Azure Cache for Redis via Spring Session
        // (enabled by @EnableRedisHttpSession above). session.setAttribute writes to the shared
        // Redis store, so any instance in the cluster can read this state — no server affinity needed.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 FIX: Cache booking in Azure Cache for Redis with TTL instead of a
        // local in-memory HashMap. The TTL (default 30 min) prevents indefinite memory growth
        // and ensures stale entries are automatically evicted. All instances share the same
        // distributed cache, eliminating per-instance inconsistency.
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, Duration.ofMinutes(bookingCacheTtlMinutes));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: Reading from Redis-backed session store via Spring Session.
        // The guestName attribute is now retrieved from Azure Cache for Redis, making it
        // available across all instances regardless of which node handled the original request.
        String lastGuest = (String) session.getAttribute("guestName");

        // cr-java-0067 FIX: Retrieve cached booking from Azure Cache for Redis.
        // Falls back to the database via bookingService if the entry has expired or is absent.
        String cacheKey = "booking:" + bookingId;
        @SuppressWarnings("unchecked")
        Map<String, Object> cachedBooking = (Map<String, Object>) redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: Hardcoded URL replaced with value injected from Azure App Configuration
        // via the app.inventory.service.url property (backed by AZURE_INVENTORY_SERVICE_URL env var).
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
