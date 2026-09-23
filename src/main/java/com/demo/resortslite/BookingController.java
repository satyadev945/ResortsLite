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

@RestController
@RequestMapping("/api/bookings")
@EnableRedisHttpSession
// cr-java-0065 FIX: HTTP session state is now backed by Amazon ElastiCache for Redis via
// Spring Session. All session.setAttribute / session.getAttribute calls are transparently
// serialised to Redis, enabling stateless application instances, horizontal scaling, and
// zero session-data loss during auto-scaling events or instance termination.
// Configure the Redis endpoint via environment variables:
//   SPRING_REDIS_HOST  (ElastiCache primary endpoint, e.g. my-cluster.abc123.ng.0001.use1.cache.amazonaws.com)
//   SPRING_REDIS_PORT  (default 6379)
//   SPRING_REDIS_PASSWORD (if AUTH is enabled on the cluster)
// Spring Session intercepts the standard HttpSession API — no business-logic changes required.
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 FIX: Replaced unbounded static in-memory HashMap cache with
    // Amazon ElastiCache for Redis via Spring Data RedisTemplate.
    // RedisTemplate stores booking entries in the shared ElastiCache cluster so that
    // all EC2 instances in the Auto Scaling group share a single, consistent cache view.
    // Each entry is written with a configurable TTL (default 30 minutes) to prevent
    // indefinite memory growth and stale-data inconsistencies across instances.
    // Configure via environment variables:
    //   SPRING_REDIS_HOST     — ElastiCache primary endpoint hostname
    //   SPRING_REDIS_PORT     — Redis port (default 6379)
    //   BOOKING_CACHE_TTL_MINUTES — cache entry TTL in minutes (default 30)
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${booking.cache.ttl-minutes:${BOOKING_CACHE_TTL_MINUTES:30}}")
    private long bookingCacheTtlMinutes;

    // cr-java-0071 FIX: Hard-coded environment URL externalized to AWS SSM Parameter Store.
    // Value is resolved from application property backed by SSM: /resortslite/inventory/service-url
    // Override via environment variable APP_INVENTORY_SERVICE_URL or property app.inventory.service-url
    @Value("${app.inventory.service-url:${APP_INVENTORY_SERVICE_URL:http://inventory-service.internal:8081/rooms/available}}")
    private String inventoryServiceUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: session.setAttribute calls are now backed by Amazon ElastiCache
        // for Redis via Spring Session. The HttpSession API is unchanged; Spring Session
        // transparently serialises all attributes to the shared Redis cluster so every
        // application instance in the AWS ALB target group reads the same session data.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 FIX: Cache booking in Amazon ElastiCache for Redis with TTL.
        // The entry is stored under the key "booking:<bookingId>" and automatically
        // expires after bookingCacheTtlMinutes minutes, preventing unbounded memory
        // growth and ensuring stale entries are evicted consistently across all instances.
        String cacheKey = "booking:" + booking.get("bookingId");
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

        // cr-java-0065 FIX: session.getAttribute is now served from Amazon ElastiCache for
        // Redis via Spring Session. The value is consistent across all EC2 instances in the
        // cluster — no more null returns when the request lands on a different instance.
        String lastGuest = (String) session.getAttribute("guestName");

        // cr-java-0067 FIX: Retrieve booking from Amazon ElastiCache for Redis.
        // Falls back to the database lookup via bookingService when the cache entry
        // has expired or is absent (cache-aside pattern).
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
        // cr-java-0071 FIX: URL is no longer hard-coded. It is injected from AWS SSM Parameter Store
        // via the property app.inventory.service-url (SSM path: /resortslite/inventory/service-url).
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
