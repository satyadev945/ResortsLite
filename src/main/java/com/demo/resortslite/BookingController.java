package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.web.bind.annotation.*;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// cr-java-0065 FIX: @EnableRedisHttpSession activates Spring Session with Redis as the
// backing store.  All HttpSession operations (getAttribute / setAttribute) are now
// transparently delegated to Amazon ElastiCache for Redis, making every application
// instance stateless and enabling horizontal scaling without sticky sessions.
@EnableRedisHttpSession
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 FIX: Static in-memory HashMap cache replaced with Amazon ElastiCache
    // for Redis via Spring's RedisTemplate.  Benefits over the previous HashMap:
    //   1. Shared across ALL application instances — no instance-local blind spots.
    //   2. TTL (configurable via app.cache.booking.ttl-minutes) enforces automatic
    //      expiration, preventing indefinite memory growth and stale data.
    //   3. Centralized cache management — entries survive instance restarts and
    //      auto-scaling events without any warm-up period.
    // The RedisTemplate<String, Object> bean is auto-configured by Spring Boot when
    // spring-boot-starter-data-redis is on the classpath and spring.redis.host is set.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // TTL for booking cache entries in Amazon ElastiCache for Redis.
    // Defaults to 60 minutes; override via environment variable BOOKING_CACHE_TTL_MINUTES
    // or the Spring property app.cache.booking.ttl-minutes.
    @Value("${app.cache.booking.ttl-minutes:${BOOKING_CACHE_TTL_MINUTES:60}}")
    private long bookingCacheTtlMinutes;

    // Redis key prefix for booking cache entries — avoids collisions with other keys.
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:";

    // cr-java-0071 FIX: SSM Parameter Store parameter name for the inventory service URL.
    // The parameter name is resolved from the environment variable INVENTORY_URL_PARAM_NAME
    // (or the Spring property app.ssm.inventory-url-param).  At runtime the actual URL is
    // fetched from AWS Systems Manager Parameter Store so no environment-specific URL ever
    // appears in source code or version control.
    @Value("${app.ssm.inventory-url-param:${INVENTORY_URL_PARAM_NAME:/resortslite/inventory/service-url}}")
    private String inventoryUrlParamName;

    // cr-java-0071 FIX: AWS region used to build the SSM client.
    @Value("${cloud.aws.region.static:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Retrieves the inventory service URL from AWS Systems Manager Parameter Store.
     * Replaces the hard-coded URL "http://inventory-service.internal:8081/rooms/available"
     * (cr-java-0071, line 66) with a dynamic lookup so the same artifact can be deployed
     * to dev, staging, and production without code changes.
     *
     * @return the inventory service URL stored in Parameter Store
     */
    private String getInventoryUrlFromSsm() {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetParameterRequest request = GetParameterRequest.builder()
                    .name(inventoryUrlParamName)
                    .withDecryption(false)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        }
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session attributes are now stored in Amazon ElastiCache for
        // Redis via Spring Session (enabled by @EnableRedisHttpSession above).  The
        // HttpSession API is unchanged — Spring Session transparently serialises and
        // persists these attributes to the shared Redis cluster so every application
        // instance can read them, eliminating server affinity and enabling safe
        // horizontal scaling and failover.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        // cr-java-0067 FIX: Booking entry stored in Amazon ElastiCache for Redis with a
        // configurable TTL (default 60 minutes).  The RedisTemplate opsForValue().set()
        // call writes the booking map to the shared Redis cluster under the key
        // "booking:<bookingId>" and schedules automatic expiration after
        // bookingCacheTtlMinutes minutes.  This replaces the previous unbounded static
        // HashMap which grew indefinitely and was invisible to other EC2 instances.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
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

        // cr-java-0065 FIX: Session attribute is now read from Amazon ElastiCache for
        // Redis via Spring Session.  Any instance in the cluster can serve this request
        // and will retrieve the correct guestName regardless of which instance handled
        // the original /create call.
        String lastGuest = (String) session.getAttribute("guestName");

        // cr-java-0067 FIX: Booking details retrieved from Amazon ElastiCache for Redis
        // using the same key prefix used during storage.  Any application instance can
        // serve this request — the cache is shared and centrally managed.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + bookingId;
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("cachedBooking", cachedBooking);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX: Hard-coded environment URL replaced with AWS Systems Manager
        // Parameter Store lookup.  The parameter /resortslite/inventory/service-url (or the
        // name configured via INVENTORY_URL_PARAM_NAME) holds the actual endpoint value,
        // enabling environment-agnostic deployments without code changes.
        String inventoryUrl = getInventoryUrlFromSsm(); // cr-java-0071

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
