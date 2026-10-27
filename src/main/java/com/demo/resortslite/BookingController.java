package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0065 FIX: Replaced HttpSession with StringRedisTemplate to externalise
    // session state to Azure Cache for Redis. All instances share the same Redis store,
    // enabling stateless horizontal scaling and safe auto-scaling / failover on Azure.
    // cr-java-0067 FIX: Also used for distributed booking cache with TTL — replaces the
    // former static HashMap (bookingCache) that had no expiration policy and was
    // instance-local, causing stale data and memory growth in multi-instance deployments.
    @Autowired
    private StringRedisTemplate redisTemplate;

    // cr-java-0071 FIX: Externalized inventory service URL to Azure App Configuration.
    // Previously hard-coded as "http://inventory-service.internal:8081/rooms/available".
    // Now injected via @Value from application.properties / Azure App Configuration,
    // enabling environment-agnostic deployments without code changes.
    @Value("${app.inventory.available-url:${app.inventory.endpoint:/rooms/available}}")
    private String inventoryAvailableUrl;

    // cr-java-0067 FIX: Removed static HashMap bookingCache — replaced with Azure Cache
    // for Redis via StringRedisTemplate. Redis provides:
    //   • Distributed visibility: all instances share the same cache store.
    //   • TTL enforcement: cache entries expire automatically (30 minutes below).
    //   • Memory safety: expired entries are evicted by Redis, preventing OOM errors.
    // The former declaration was:
    //   private static final Map<String, Object> bookingCache = new HashMap<>();

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        String bookingId = (String) booking.get("bookingId");

        // cr-java-0065 FIX: Booking state is now stored in Azure Cache for Redis instead of
        // the in-process HTTP session. A TTL of 30 minutes mirrors typical session expiry.
        // All application instances share the same Redis store, so any instance can serve
        // subsequent requests without sticky sessions or server affinity.
        redisTemplate.opsForValue().set("session:lastBooking:" + bookingId, booking.toString(), 30, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set("session:guestName:" + bookingId, guestName, 30, TimeUnit.MINUTES);

        // cr-java-0067 FIX: Booking details are cached in Azure Cache for Redis with a
        // 30-minute TTL. This replaces the former static HashMap (bookingCache) which:
        //   • Had no TTL — entries accumulated indefinitely, risking OOM errors.
        //   • Was instance-local — other instances in the cluster could not see cached data.
        //   • Caused stale data inconsistencies across horizontally scaled instances.
        // Redis ensures cache entries are shared across all instances and automatically
        // evicted after the TTL, preventing memory exhaustion and stale data issues.
        redisTemplate.opsForValue().set("cache:booking:" + bookingId, booking.toString(), 30, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // cr-java-0065 FIX: Guest name is now retrieved from Azure Cache for Redis instead of
        // the in-process HTTP session. This ensures consistent reads across all instances in
        // the cluster, supporting stateless horizontal scaling on Azure Container Apps / AKS.
        String lastGuest = redisTemplate.opsForValue().get("session:guestName:" + bookingId);

        // cr-java-0067 FIX: Booking details are retrieved from the distributed Redis cache
        // instead of the former instance-local static HashMap. Returns null (cache miss) if
        // the TTL has expired, at which point the caller falls back to bookingService.
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
        // cr-java-0071 FIX: Hard-coded environment URL replaced with value injected from
        // Azure App Configuration / application.properties via @Value.
        // Set app.inventory.available-url in Azure App Configuration per environment.
        String inventoryUrl = inventoryAvailableUrl;

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
