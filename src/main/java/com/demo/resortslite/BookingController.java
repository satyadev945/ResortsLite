package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0065 fix: RedisTemplate is used to store and retrieve session-scoped data
     * in Amazon ElastiCache for Redis via Spring Session. This replaces HttpSession-based
     * state storage, enabling stateless application instances that can be horizontally
     * scaled behind an AWS ALB without server affinity.
     *
     * cr-java-0067 fix: RedisTemplate is also used as the centralized cache store with
     * explicit TTL (time-to-live) policies, replacing the unbounded static in-memory
     * HashMap (bookingCache). This eliminates indefinite memory growth, prevents stale
     * data inconsistencies across multiple instances, and ensures cache entries expire
     * automatically via Amazon ElastiCache for Redis TTL management.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * cr-java-0071 fix: Inventory service URL externalized via AWS Systems Manager Parameter Store.
     * The value is injected from the environment variable INVENTORY_SERVICE_URL or the
     * application property app.inventory.service-url, which is backed by SSM Parameter Store
     * at the path /resortslite/inventory/service-url.
     * This replaces the hard-coded URL "http://inventory-service.internal:8081/rooms/available".
     */
    @Value("${app.inventory.service-url:${INVENTORY_SERVICE_URL:http://inventory-service.internal:8081/rooms/available}}")
    private String inventoryServiceUrl;

    /**
     * cr-java-0067 fix: TTL for booking cache entries stored in Amazon ElastiCache for Redis.
     * Configurable via the environment variable BOOKING_CACHE_TTL_MINUTES or the property
     * app.cache.booking-ttl-minutes. Defaults to 60 minutes if not set.
     * SSM Parameter Store path: /resortslite/cache/booking-ttl-minutes
     */
    @Value("${app.cache.booking-ttl-minutes:${BOOKING_CACHE_TTL_MINUTES:60}}")
    private long bookingCacheTtlMinutes;

    // cr-java-0067 FIX: Removed unbounded static in-memory HashMap (bookingCache).
    // Booking data is now cached in Amazon ElastiCache for Redis via RedisTemplate
    // with an explicit TTL (bookingCacheTtlMinutes). This ensures:
    //   - Controlled expiration — entries are automatically evicted after TTL expires.
    //   - Cross-instance consistency — all horizontally scaled EC2 instances share the
    //     same centralized Redis cache, eliminating stale data divergence.
    //   - No unbounded memory growth — Redis enforces memory limits and eviction policies.
    // Redis cache key pattern: cache:booking:<bookingId>

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 fix: Session state is now stored in Amazon ElastiCache for Redis
        // via RedisTemplate, replacing HttpSession. This enables stateless instances that
        // can be load-balanced across multiple EC2 nodes without server affinity.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForHash().put("session:booking:" + bookingId, "lastBooking", booking);
        redisTemplate.opsForHash().put("session:booking:" + bookingId, "guestName", guestName);

        // cr-java-0067 fix: Booking data is cached in Amazon ElastiCache for Redis with an
        // explicit TTL instead of the former unbounded static in-memory HashMap. The TTL
        // ensures automatic expiration, preventing stale data and unbounded memory growth.
        // All instances share this centralized cache, maintaining consistency across the cluster.
        redisTemplate.opsForValue().set(
                "cache:booking:" + bookingId,
                booking,
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

        // cr-java-0065 fix: Guest name is now retrieved from Amazon ElastiCache for Redis
        // via RedisTemplate instead of HttpSession, ensuring consistent reads across all
        // horizontally scaled instances behind the AWS ALB.
        String lastGuest = (String) redisTemplate.opsForHash().get("session:booking:" + bookingId, "guestName");

        // cr-java-0067 fix: Booking details are retrieved from the centralized Redis cache
        // (with TTL) instead of the former instance-local in-memory HashMap. If the cache
        // entry has expired or is absent, the request falls through to bookingService.
        Object cachedBooking = redisTemplate.opsForValue().get("cache:booking:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 fix: URL is no longer hard-coded. It is read from the injected field
        // inventoryServiceUrl, which is sourced from AWS SSM Parameter Store via the
        // environment variable INVENTORY_SERVICE_URL or property app.inventory.service-url.
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
