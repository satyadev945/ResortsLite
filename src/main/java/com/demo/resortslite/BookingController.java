package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

// cr-java-0065 FIX: @EnableRedisHttpSession delegates all HttpSession storage to
// Amazon ElastiCache for Redis via Spring Session. Every setAttribute / getAttribute
// call is transparently serialised to Redis, so any EC2 instance in the Auto Scaling
// Group can serve any request without sticky sessions on the ALB.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
@EnableRedisHttpSession
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0071 FIX: SsmClient injected to retrieve environment-specific URLs
    // from AWS Systems Manager Parameter Store at runtime, replacing hard-coded values.
    @Autowired
    private SsmClient ssmClient;

    // cr-java-0067 FIX: RedisTemplate replaces the static in-memory HashMap cache.
    // All booking cache entries are stored in Amazon ElastiCache for Redis with a
    // configurable TTL, ensuring:
    //   - Controlled memory growth (entries expire automatically after TTL elapses)
    //   - Consistent cache state across all EC2 instances in the Auto Scaling Group
    //   - Centralised cache management via the shared ElastiCache cluster
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cr-java-0067 FIX: TTL for booking cache entries (default 30 minutes).
    // Override via BOOKING_CACHE_TTL_SECONDS environment variable or
    // app.cache.booking-ttl-seconds application property.
    @Value("${app.cache.booking-ttl-seconds:1800}")
    private long bookingCacheTtlSeconds;

    // cr-java-0071 FIX: SSM parameter name for the inventory service URL is externalised
    // via the environment variable INVENTORY_URL_PARAM_NAME (or application property).
    // Default parameter path: /resortslite/inventory/url
    @Value("${app.ssm.inventory-url-param:/resortslite/inventory/url}")
    private String inventoryUrlParamName;

    // cr-java-0067 FIX: Redis key prefix for booking cache entries.
    // Namespacing prevents collisions with other keys stored in the same Redis cluster.
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:cache:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: HttpSession is now backed by Amazon ElastiCache for Redis
        // via Spring Session (@EnableRedisHttpSession). setAttribute calls are serialised
        // to the shared Redis cluster, making session data available to every instance
        // in the Auto Scaling Group — no server affinity or sticky sessions required.
        session.setAttribute("lastBooking", booking); // cr-java-0065 resolved — stored in Redis
        session.setAttribute("guestName", guestName); // cr-java-0065 resolved — stored in Redis

        // cr-java-0067 FIX: Booking is cached in Amazon ElastiCache for Redis with a TTL.
        // RedisTemplate.opsForValue().set(..., ttl, TimeUnit) atomically writes the value
        // and schedules its expiry, preventing unbounded memory growth and ensuring all
        // instances share the same cache view.
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

        // cr-java-0065 FIX: getAttribute now reads from the centralised Redis session store
        // (Amazon ElastiCache). Any EC2 instance can serve this request and retrieve the
        // correct guest name regardless of which instance handled the original POST.
        String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065 resolved — read from Redis

        // cr-java-0067 FIX: Booking details are read from the shared ElastiCache Redis cache.
        // If the entry has expired (TTL elapsed) or was never cached, the value will be null
        // and the service layer is consulted directly, preserving existing business logic.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
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
        // cr-java-0071 FIX: Hard-coded environment URL replaced with a value retrieved
        // from AWS Systems Manager Parameter Store at runtime.
        // Original violation (line 66): String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";
        // The parameter name is configured via app.ssm.inventory-url-param (default: /resortslite/inventory/url).
        GetParameterRequest paramRequest = GetParameterRequest.builder()
                .name(inventoryUrlParamName)
                .withDecryption(false)
                .build();
        GetParameterResponse paramResponse = ssmClient.getParameter(paramRequest);
        String inventoryUrl = paramResponse.parameter().value(); // cr-java-0071 resolved

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
