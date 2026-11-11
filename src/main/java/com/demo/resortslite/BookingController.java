package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @Value("${app.report.base-path}")
    private String reportBasePath; // injected from REPORT_BASE_PATH (ConfigMap)

    // cz-java-0070: Local in-process cache replaced by shared Amazon ElastiCache (Redis) cache
    // so cached bookings are visible to all pods/instances. Connection details come from
    // REDIS_HOST / REDIS_PORT / REDIS_PASSWORD / REDIS_SSL_ENABLED (ConfigMap/Secret).
    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.cache.booking.key-prefix:resortslite:booking:}")
    private String bookingCacheKeyPrefix;

    @Value("${app.cache.booking.ttl-seconds:3600}")
    private long bookingCacheTtlSeconds;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069: HttpSession is backed by Spring Session + Amazon ElastiCache (Redis)
        // (see spring.session.store-type=redis), so session state is shared across all
        // pods/instances and survives container restarts. Attributes must be Serializable.
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        try {
            redisTemplate.opsForValue().set(
                    bookingCacheKeyPrefix + booking.get("bookingId"),
                    objectMapper.writeValueAsString(booking),
                    Duration.ofSeconds(bookingCacheTtlSeconds));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to cache booking in Redis", e);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cz-java-0069: Session is externalized to Redis via Spring Session; readable from any pod.
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
}
