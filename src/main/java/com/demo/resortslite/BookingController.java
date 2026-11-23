package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Report base path externalised via REPORT_BASE_PATH (ConfigMap-injected env var)
    @Value("${app.report.base-path:./reports/}")
    private String reportBasePath;

    // cz-java-0070: Booking cache externalized from in-process HashMap to Amazon ElastiCache (Redis)
    // so it is shared across all pods. Connection details come from REDIS_HOST/REDIS_PORT/REDIS_PASSWORD
    // (ConfigMap/Secret-injected env vars); TTL is configurable via BOOKING_CACHE_TTL_SECONDS.
    private static final String CACHE_KEY_PREFIX = "resortslite:booking:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.booking-cache.ttl-seconds:3600}")
    private long bookingCacheTtlSeconds;

    private void cacheBooking(String bookingId, Map<String, Object> booking) {
        try {
            redisTemplate.opsForValue().set(CACHE_KEY_PREFIX + bookingId,
                    objectMapper.writeValueAsString(booking),
                    Duration.ofSeconds(bookingCacheTtlSeconds));
        } catch (Exception e) {
            // Cache is best-effort; booking is already persisted in the database.
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

        // cz-java-0063: HttpSession is backed by Spring Session + ElastiCache (Redis), so session
        // state is shared across all pods. Attributes must be Serializable.
        session.setAttribute("lastBooking", new HashMap<>(booking));
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

        // cz-java-0063: session is externalized to Redis via Spring Session.
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
