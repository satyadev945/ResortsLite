package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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

    // cr-java-0065: Replaced HttpSession with RedisTemplate for distributed session state.
    // Amazon ElastiCache for Redis (via Spring Session) stores session data centrally,
    // enabling stateless application instances that can be horizontally scaled across
    // multiple EC2 instances behind an AWS ALB without server affinity.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cr-java-0067: Replaced instance-local HashMap cache with Amazon ElastiCache for Redis.
    // Booking entries are stored under namespaced keys ("booking:<bookingId>") with a
    // configurable TTL so that Redis automatically evicts stale entries. This ensures
    // cache consistency across all horizontally-scaled EC2 instances and prevents
    // unbounded memory growth that caused OOM errors in the previous implementation.
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:";
    private static final long BOOKING_CACHE_TTL_SECONDS = Long.parseLong(
            System.getenv().getOrDefault("BOOKING_CACHE_TTL_SECONDS", "3600"));

    // cr-java-0071: Replaced hard-coded URL "http://inventory-service.internal:8081/rooms/available"
    // with a value injected from AWS Systems Manager Parameter Store via AwsSsmParameterStoreConfig.
    // The actual URL is resolved at startup from SSM parameter /resortslite/inventory/url,
    // enabling environment-agnostic deployments without code changes per environment.
    @Autowired
    @Qualifier("inventoryServiceUrl")
    private String inventoryServiceUrl;

    // Session TTL in seconds — configurable via environment variable (default: 30 minutes).
    // ElastiCache for Redis will automatically expire session keys after this duration.
    private static final long SESSION_TTL_SECONDS = Long.parseLong(
            System.getenv().getOrDefault("SESSION_TTL_SECONDS", "1800"));

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false, defaultValue = "") String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065: Session state migrated from HttpSession (instance-local) to
        // Amazon ElastiCache for Redis via RedisTemplate. Session attributes are stored
        // under a namespaced key using the client-supplied X-Session-Id header, with a
        // configurable TTL so that Redis automatically evicts stale sessions.
        // This enables stateless EC2 instances — any instance can serve any request.
        if (!sessionId.isEmpty()) {
            redisTemplate.opsForHash().put("session:" + sessionId, "lastBooking", booking);
            redisTemplate.opsForHash().put("session:" + sessionId, "guestName", guestName);
            redisTemplate.expire("session:" + sessionId, SESSION_TTL_SECONDS, TimeUnit.SECONDS);
        }

        // cr-java-0067: Cache booking in Amazon ElastiCache for Redis with TTL.
        // Replaces the former instance-local HashMap (bookingCache) with a distributed
        // Redis entry that expires automatically after BOOKING_CACHE_TTL_SECONDS seconds.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, BOOKING_CACHE_TTL_SECONDS, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false, defaultValue = "") String sessionId) {

        // cr-java-0065: Reading session state from Amazon ElastiCache for Redis instead of
        // HttpSession. The guestName is retrieved from the distributed Redis store using the
        // client-supplied X-Session-Id header, ensuring consistent reads across all EC2
        // instances in the cluster regardless of which instance handled the original request.
        String lastGuest = null;
        if (!sessionId.isEmpty()) {
            Object guestValue = redisTemplate.opsForHash().get("session:" + sessionId, "guestName");
            lastGuest = guestValue != null ? guestValue.toString() : null;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071: Hard-coded URL "http://inventory-service.internal:8081/rooms/available"
        // replaced with inventoryServiceUrl injected from AWS SSM Parameter Store.
        // SSM parameter: /resortslite/inventory/url (configured in AwsSsmParameterStoreConfig).
        // This enables the same binary to be deployed across dev/staging/production environments
        // by updating the SSM parameter value — no code change or redeployment required.
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
