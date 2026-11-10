package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;

// cz-java-0063: HttpSession is transparently backed by Spring Session + ElastiCache (Redis)
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @Value("${REPORT_BASE_PATH:${app.report.base-path:reports/}}")
    private String reportBasePath; // czr-java-001 (externalised via ConfigMap env var REPORT_BASE_PATH)

    // cz-java-0070: local in-memory cache replaced by Amazon ElastiCache (Redis) so the cache is
    // shared across all pods. Connection details come from ConfigMap/Secret env vars (REDIS_HOST, ...).
    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${BOOKING_CACHE_TTL_SECONDS:3600}")
    private long bookingCacheTtlSeconds;

    @Value("${BOOKING_CACHE_PREFIX:resortslite:booking:}")
    private String bookingCachePrefix;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0063: session is externalised to ElastiCache (Redis) via Spring Session,
        // so session data is shared across all pods/instances.
        // cz-java-0069: attributes are stored in the Redis-backed Spring Session (serializable HashMap/String values)
        session.setAttribute("lastBooking", new HashMap<>(booking)); // cz-java-0069
        session.setAttribute("guestName", guestName); // cz-java-0069

        cacheBooking((String) booking.get("bookingId"), booking); // cz-java-0070

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cz-java-0063: session read from Redis-backed Spring Session (shared across pods).
        String lastGuest = (String) session.getAttribute("guestName"); // cr-java-0065

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
        String inventoryUrl = "http://inventory-service.internal:8081/rooms/available"; // cr-java-0088

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
        String reportPath = reportBasePath + month + "_bookings.pdf"; // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    // cz-java-0070: write-through to shared Redis cache with TTL; cache failures must not break booking
    private void cacheBooking(String bookingId, Map<String, Object> booking) {
        try {
            redisTemplate.opsForValue().set(bookingCachePrefix + bookingId,
                    objectMapper.writeValueAsString(booking), Duration.ofSeconds(bookingCacheTtlSeconds));
        } catch (Exception e) {
            // best-effort cache; ignore failures
        }
    }
}
