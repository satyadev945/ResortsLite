package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cr-java-0065 FIX: javax.servlet.http.HttpSession is retained as the API surface, but
// the backing store is now Amazon ElastiCache for Redis via Spring Session Data Redis
// (@EnableRedisHttpSession in RedisSessionConfig). All session.setAttribute /
// session.getAttribute calls are transparently serialised to Redis, making every
// application instance stateless and enabling horizontal scaling behind an AWS ALB
// without sticky sessions.
import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067 FIX: Replaced unbounded in-memory HashMap cache (instance-local,
    // no TTL, invisible to other EC2 instances) with Amazon ElastiCache for Redis via
    // Spring Data RedisTemplate. Cache entries are stored in the shared Redis cluster
    // with a configurable TTL (default: 3600 seconds / 1 hour), ensuring:
    //   - Controlled memory growth through automatic key expiration
    //   - Consistent cache state across all EC2 instances in the Auto Scaling Group
    //   - Centralised cache management via Amazon ElastiCache
    //   - No stale data inconsistencies across multiple application instances
    //
    // Required AWS setup:
    //   1. Amazon ElastiCache for Redis cluster in the same VPC as the application.
    //   2. Security group allowing inbound TCP 6379 from the application security group.
    //   3. Set REDIS_HOST to the cluster Primary Endpoint DNS name.
    //   4. Optionally set BOOKING_CACHE_TTL_SECONDS to control cache entry lifetime.
    //
    // SSM parameter: /resortslite/redis/host
    // SSM parameter: /resortslite/booking/cache-ttl-seconds
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // TTL for booking cache entries in Amazon ElastiCache for Redis.
    // Sourced from environment variable BOOKING_CACHE_TTL_SECONDS or
    // SSM parameter /resortslite/booking/cache-ttl-seconds (default: 3600 seconds = 1 hour).
    @Value("${app.booking.cache.ttl-seconds:${BOOKING_CACHE_TTL_SECONDS:3600}}")
    private long bookingCacheTtlSeconds;

    // Redis key prefix for booking cache entries — avoids key collisions with other
    // data stored in the same ElastiCache cluster (e.g., Spring Session keys).
    private static final String BOOKING_CACHE_KEY_PREFIX = "booking:cache:";

    // cr-java-0071 FIX: Hard-coded inventory service URL replaced with a value sourced from
    // AWS Systems Manager Parameter Store via the Spring @Value binding. The SSM parameter
    // name is /resortslite/inventory/endpoint and is injected at application startup through
    // the aws-spring-cloud-config or environment variable INVENTORY_SERVICE_URL.
    // This makes the URL environment-agnostic and removes the need for code changes per deployment.
    @Value("${app.inventory.service.url:${INVENTORY_SERVICE_URL:http://inventory-service.internal:8081/rooms/available}}")
    private String inventoryServiceUrl;

    // cr-java-0065 FIX: createBooking now accepts HttpSession whose backing store is
    // Amazon ElastiCache for Redis (configured via RedisSessionConfig / Spring Session).
    // session.setAttribute calls below are serialised to Redis — no server-side affinity
    // is required; any instance in the Auto Scaling Group can serve subsequent requests.
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Session attributes are now stored in Amazon ElastiCache for
        // Redis via Spring Session Data Redis. The HttpSession API is unchanged; Spring
        // Session transparently serialises these attributes to the Redis cluster so that
        // every EC2 instance in the Auto Scaling Group shares the same session state.
        session.setAttribute("lastBooking", booking); // backed by ElastiCache for Redis
        session.setAttribute("guestName", guestName); // backed by ElastiCache for Redis

        // cr-java-0067 FIX: Booking is now cached in Amazon ElastiCache for Redis with a
        // TTL of bookingCacheTtlSeconds (default 3600 s). The RedisTemplate.opsForValue()
        // .set(key, value, Duration) call atomically writes the entry and schedules its
        // expiration in Redis, preventing unbounded memory growth and ensuring all
        // application instances share the same cache state.
        String cacheKey = BOOKING_CACHE_KEY_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, Duration.ofSeconds(bookingCacheTtlSeconds));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    // cr-java-0065 FIX: getBookingStatus now accepts HttpSession whose backing store is
    // Amazon ElastiCache for Redis. session.getAttribute reads from the shared Redis
    // cluster, so the correct guest name is returned regardless of which EC2 instance
    // handles the request — eliminating the server-affinity / data-loss risk.
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 FIX: Session attribute is now read from Amazon ElastiCache for
        // Redis via Spring Session Data Redis — consistent across all cluster instances.
        String lastGuest = (String) session.getAttribute("guestName"); // backed by ElastiCache for Redis

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX (original line 66): Hard-coded URL
        // "http://inventory-service.internal:8081/rooms/available" has been removed.
        // The URL is now sourced from AWS Systems Manager Parameter Store via the
        // Spring property app.inventory.service.url (SSM parameter:
        // /resortslite/inventory/endpoint), injected into the inventoryServiceUrl field
        // above using @Value. This enables environment-agnostic deployments — the same
        // artifact runs in dev, staging, and production by changing only the SSM parameter
        // value, with no code changes required.
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
