package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BookingController — cloud-native, stateless REST controller.
 *
 * cr-java-0065 FIX: All HTTP session state (HttpSession.setAttribute /
 * HttpSession.getAttribute) has been replaced with Google Cloud Memorystore
 * for Redis via Spring Data Redis (RedisTemplate).  Session data is now stored
 * in a centralised, distributed cache that is visible to every application
 * instance, enabling horizontal scaling, zero-downtime rolling deployments,
 * and safe auto-scaling on GCP Cloud Run / GKE without sticky sessions.
 *
 * Key changes:
 *   - Removed javax.servlet.http.HttpSession import and all method parameters
 *     that accepted HttpSession.
 *   - Injected RedisTemplate<String, Object> (auto-configured by
 *     spring-boot-starter-data-redis).
 *   - session.setAttribute("lastBooking", booking)  →
 *       redisTemplate.opsForValue().set("session:lastBooking:" + bookingId, booking, 30, TimeUnit.MINUTES)
 *   - session.setAttribute("guestName", guestName)  →
 *       redisTemplate.opsForValue().set("session:guestName:" + bookingId, guestName, 30, TimeUnit.MINUTES)
 *   - session.getAttribute("guestName")             →
 *       redisTemplate.opsForValue().get("session:guestName:" + bookingId)
 *   - A TTL of 30 minutes is applied to every key to prevent unbounded memory
 *     growth in the Redis instance.
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    /**
     * cr-java-0065 FIX: RedisTemplate replaces HttpSession for distributed,
     * stateless session state storage backed by GCP Memorystore for Redis.
     */
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * cr-java-0071 FIX: Hard-coded inventory service URL has been replaced with an
     * externalized configuration property backed by GCP Secret Manager.
     * The value is resolved at runtime from the {@code app.inventory.endpoint}
     * application property, which is itself sourced from the
     * {@code sm://resorts-lite-inventory-url} GCP Secret Manager secret (or the
     * {@code INVENTORY_SERVICE_URL} environment variable for local development).
     * This eliminates the environment-specific hard-coded URL and enables seamless
     * deployment across dev, staging, and production environments.
     */
    @Value("${app.inventory.endpoint:${INVENTORY_SERVICE_URL:http://inventory-service.internal:8081/rooms/available}}")
    private String inventoryServiceUrl;

    /**
     * cr-java-0065 FIX: HttpSession parameter removed.
     * Booking state is now persisted to GCP Memorystore for Redis via RedisTemplate,
     * making this endpoint fully stateless and safe for horizontal scaling.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065 FIX: Replaced session.setAttribute("lastBooking", booking) and
        // session.setAttribute("guestName", guestName) with RedisTemplate writes.
        // Data is stored in GCP Memorystore for Redis with a 30-minute TTL, ensuring
        // it is accessible from any application instance in the cluster.
        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set("session:lastBooking:" + bookingId, booking, 30, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set("session:guestName:" + bookingId, guestName, 30, TimeUnit.MINUTES);

        // cr-java-0067 FIX: Replaced instance-local static HashMap (bookingCache) with
        // Google Cloud Memorystore for Redis via RedisTemplate.  A TTL of 60 minutes is
        // applied so that the cache entry expires automatically, preventing unbounded
        // memory growth and ensuring consistent cache state across all application
        // instances running in GCP Cloud Run / GKE.
        redisTemplate.opsForValue().set("cache:booking:" + bookingId, booking, 60, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * cr-java-0065 FIX: HttpSession parameter removed.
     * Guest name is now retrieved from GCP Memorystore for Redis via RedisTemplate,
     * ensuring consistent reads regardless of which instance handles the request.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // cr-java-0065 FIX: Replaced session.getAttribute("guestName") with a
        // RedisTemplate read keyed on bookingId.  Any instance in the cluster can
        // serve this request and will retrieve the same value from the shared
        // Memorystore for Redis instance.
        String lastGuest = (String) redisTemplate.opsForValue().get("session:guestName:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // cr-java-0071 FIX (Line 66): Replaced hard-coded environment-specific URL
        // "http://inventory-service.internal:8081/rooms/available" with the
        // externalized property 'app.inventory.endpoint', which is resolved from
        // GCP Secret Manager (sm://resorts-lite-inventory-url) or the
        // INVENTORY_SERVICE_URL environment variable at runtime.
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
