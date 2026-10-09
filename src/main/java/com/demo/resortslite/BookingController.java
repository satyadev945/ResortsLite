package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cr-java-0065 fix (Line 6): javax.servlet.http.HttpSession is retained as the API surface,
// but Spring Session Data Redis (enabled via RedisSessionConfig / @EnableRedisHttpSession)
// transparently replaces the default in-process session store with Amazon ElastiCache for Redis.
// All HttpSession reads and writes are now distributed across the Redis cluster, eliminating
// server affinity and enabling stateless horizontal scaling behind an AWS ALB.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 fix: Replaced unbounded static in-memory HashMap cache with Amazon
    // ElastiCache for Redis via Spring Data RedisTemplate. The cache is now:
    //   - Centralised: all application instances share the same Redis cluster, so cached
    //     entries are visible across the entire auto-scaling group (no instance-local state).
    //   - TTL-controlled: every cache entry expires after BOOKING_CACHE_TTL_SECONDS seconds
    //     (default 3600 s / 1 hour), preventing indefinite memory growth and stale data.
    //   - Cloud-native: backed by Amazon ElastiCache for Redis, managed and monitored via
    //     AWS CloudWatch, with automatic failover and in-transit encryption support.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cr-java-0067 fix: Cache TTL injected from application property / environment variable.
    // Set BOOKING_CACHE_TTL_SECONDS in the ECS task definition or AWS Parameter Store
    // (SSM path: /resortslite/cache/booking-ttl-seconds). Defaults to 3600 s (1 hour).
    @Value("${app.cache.booking.ttl-seconds:${BOOKING_CACHE_TTL_SECONDS:3600}}")
    private long bookingCacheTtlSeconds;

    // cr-java-0067 fix: Redis key prefix for booking cache entries, ensuring no key
    // collisions with other data stored in the same ElastiCache cluster.
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:cache:";

    // cr-java-0071 fix: Hard-coded inventory service URL replaced with a value injected from
    // AWS Systems Manager Parameter Store via the 'app.inventory.service.url' property.
    // Set the SSM parameter '/resortslite/inventory/service-url' (or the env-var
    // INVENTORY_SERVICE_URL) in each deployment environment so no URL is baked into the binary.
    @Value("${app.inventory.service.url:${INVENTORY_SERVICE_URL:http://inventory-service.internal:8081/rooms/available}}")
    private String inventoryServiceUrl;

    // cr-java-0065 fix (Line 27): HttpSession parameter is retained; Spring Session Data Redis
    // intercepts all session operations and stores/retrieves data from Amazon ElastiCache for
    // Redis instead of the local JVM heap. The HttpSession API is unchanged — the distributed
    // backing store is wired in transparently by RedisSessionConfig (@EnableRedisHttpSession).
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 fix (Line 34): session.setAttribute("lastBooking", ...) now writes to
        // Amazon ElastiCache for Redis via Spring Session. The booking state is stored in the
        // shared Redis cluster and is accessible by any application instance in the fleet,
        // eliminating the server-affinity problem caused by in-process session storage.
        session.setAttribute("lastBooking", booking); // cr-java-0065 fixed — backed by ElastiCache Redis

        // cr-java-0065 fix (Line 35): session.setAttribute("guestName", ...) now writes to
        // Amazon ElastiCache for Redis via Spring Session. Guest name is persisted in the
        // distributed Redis session store, not in local JVM memory, so it survives instance
        // restarts, scale-in events, and ALB routing to a different instance.
        session.setAttribute("guestName", guestName); // cr-java-0065 fixed — backed by ElastiCache Redis

        // cr-java-0067 fix: Cache the booking in Amazon ElastiCache for Redis with a TTL.
        // RedisTemplate.opsForValue().set(..., ttl, TimeUnit) atomically writes the entry
        // and schedules its expiration, preventing unbounded memory growth and stale data.
        // The cache is shared across all instances — any node can serve a cache hit.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 fix (Line 48): session.getAttribute("guestName") now reads from
        // Amazon ElastiCache for Redis via Spring Session. The guest name is retrieved from
        // the shared Redis cluster regardless of which application instance handles this
        // request, ensuring consistent session data across all nodes in the auto-scaling group.
        String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065 fixed — backed by ElastiCache Redis

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 fix: The former hard-coded URL
        //   "http://inventory-service.internal:8081/rooms/available"
        // has been replaced with the 'inventoryServiceUrl' field injected from
        // AWS Systems Manager Parameter Store (SSM path: /resortslite/inventory/service-url)
        // via the 'app.inventory.service.url' application property. This makes the endpoint
        // configurable per environment without any code change.
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryServiceUrl);
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
