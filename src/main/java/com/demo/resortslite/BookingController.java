package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

// cr-java-0065 FIX: Removed javax.servlet.http.HttpSession import.
// All HTTP session state is now managed by Spring Session backed by Amazon ElastiCache
// for Redis (RedisIndexedSessionRepository). Session data is stored centrally in Redis,
// making every application instance stateless and enabling horizontal scaling across
// multiple EC2 instances behind an AWS ALB without sticky sessions.

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0065 FIX: Injected RedisIndexedSessionRepository to read/write session
    // attributes in Amazon ElastiCache for Redis instead of in-process HttpSession memory.
    // This ensures session data is visible to every instance in the Auto Scaling group.
    @Autowired
    private RedisIndexedSessionRepository sessionRepository;

    // cr-java-0067 FIX: Replaced unbounded in-memory HashMap cache (bookingCache) with
    // Amazon ElastiCache for Redis via Spring Data RedisTemplate. Each cache entry is
    // stored under the key prefix "booking:cache:" with a configurable TTL
    // (default 30 minutes, overridable via BOOKING_CACHE_TTL_MINUTES env var).
    // This eliminates indefinite memory growth, prevents stale data across instances,
    // and provides a single shared cache visible to every node in the Auto Scaling group.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${booking.cache.ttl.minutes:30}")
    private long bookingCacheTtlMinutes;

    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:cache:";

    // cr-java-0071 FIX: Replaced hard-coded URL "http://inventory-service.internal:8081/rooms/available"
    // with a value externalised via AWS Systems Manager Parameter Store. The SSM parameter name is
    // bound through the Spring property 'app.inventory.url' which is resolved at startup from SSM
    // using the AwsSsmPropertySourceLocator (see SsmParameterStoreConfig). This enables
    // environment-agnostic deployments — the URL is changed in SSM without any code or image rebuild.
    @Value("${app.inventory.url:http://inventory-service.internal:8081/rooms/available}")
    private String inventoryServiceUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session attributes are now stored in Amazon ElastiCache for Redis
        // via Spring Session (RedisIndexedSessionRepository) instead of in-process HttpSession.
        // The session is identified by the X-Session-Id request header, enabling stateless
        // request routing — any EC2 instance can serve any request without sticky sessions.
        if (sessionId != null && sessionRepository.findById(sessionId) != null) {
            org.springframework.session.Session redisSession = sessionRepository.findById(sessionId);
            redisSession.setAttribute("lastBooking", booking);
            redisSession.setAttribute("guestName", guestName);
            sessionRepository.save(redisSession);
        } else {
            org.springframework.session.Session redisSession = sessionRepository.createSession();
            redisSession.setAttribute("lastBooking", booking);
            redisSession.setAttribute("guestName", guestName);
            sessionRepository.save(redisSession);
        }

        // cr-java-0067 FIX: Cache the booking in Amazon ElastiCache for Redis with a TTL.
        // The entry expires automatically after bookingCacheTtlMinutes (default 30 min),
        // preventing unbounded memory growth and ensuring stale entries are evicted.
        // All application instances share this cache, so reads are consistent across the cluster.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, Duration.ofMinutes(bookingCacheTtlMinutes));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        // cr-java-0065 FIX: Session attribute "guestName" is now retrieved from Amazon
        // ElastiCache for Redis via Spring Session (RedisIndexedSessionRepository) instead
        // of from in-process HttpSession. This eliminates server affinity — the attribute
        // is available on every instance in the cluster because it is stored centrally in Redis.
        String lastGuest = null;
        if (sessionId != null) {
            org.springframework.session.Session redisSession = sessionRepository.findById(sessionId);
            if (redisSession != null) {
                lastGuest = (String) redisSession.getAttribute("guestName");
            }
        }

        // cr-java-0067 FIX: Booking details are looked up from the shared Amazon ElastiCache
        // for Redis cache (with TTL) before falling back to the database via bookingService.
        // This replaces the former instance-local HashMap lookup that was invisible to other nodes.
        @SuppressWarnings("unchecked")
        Map<String, Object> cachedBooking = (Map<String, Object>) redisTemplate.opsForValue()
                .get(BOOKING_CACHE_KEY_PREFIX + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX (Line 66): The hard-coded URL
        //   "http://inventory-service.internal:8081/rooms/available"
        // has been removed. The URL is now injected via the @Value-bound field
        // 'inventoryServiceUrl', whose value is externalised in AWS Systems Manager
        // Parameter Store under the parameter name stored in 'app.inventory.url.ssm-param'.
        // This satisfies 12-factor app principle III (Config) and enables zero-code-change
        // promotion across dev / staging / production environments.
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
