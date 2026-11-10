package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private ParameterStoreService parameterStore;

    // Inventory service URL is resolved from AWS Systems Manager Parameter Store
    @Value("${app.inventory.ssm-parameter:/resortslite/config/inventory-url}")
    private String inventoryUrlParameter;

    @Value("${app.inventory.url:${INVENTORY_SERVICE_URL:}}")
    private String inventoryUrlFallback;

    // cr-java-0067 fixed: booking cache lives in Amazon ElastiCache for Redis with a TTL,
    // so it is shared across instances and entries expire automatically.
    private static final Logger log = LoggerFactory.getLogger(BookingController.class);
    private static final ObjectMapper CACHE_MAPPER = new ObjectMapper();

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${app.cache.booking.key-prefix:resortslite:booking:}")
    private String bookingCacheKeyPrefix;

    @Value("${app.cache.booking.ttl-seconds:900}")
    private long bookingCacheTtlSeconds;

    private void cacheBooking(String bookingId, Map<String, Object> booking) {
        if (bookingId == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(bookingCacheKeyPrefix + bookingId,
                    CACHE_MAPPER.writeValueAsString(booking),
                    Duration.ofSeconds(bookingCacheTtlSeconds));
        } catch (Exception e) {
            // Cache is best-effort; never fail a booking because the cache is unavailable
            log.warn("Unable to cache booking {} in ElastiCache: {}", bookingId, e.getMessage());
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

        // cr-java-0065 fixed: HttpSession is backed by Spring Session + Amazon ElastiCache for
        // Redis, so session state is shared across all instances behind the ALB.
        // Values stored must be Serializable (HashMap of Strings).
        session.setAttribute("lastBooking", new HashMap<String, Object>(booking));
        session.setAttribute("guestName", guestName);

        cacheBooking((String) booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065 fixed: session is stored in ElastiCache Redis (Spring Session),
        // so this value is available on any instance in the cluster.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP call to
        // internal inventory service. AWS ALB, WAF, and Well-Architected security review
        // enforce HTTPS. This call will be blocked or flagged in a cloud-native setup.
        String inventoryUrl = parameterStore.getParameter(inventoryUrlParameter, inventoryUrlFallback);

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
