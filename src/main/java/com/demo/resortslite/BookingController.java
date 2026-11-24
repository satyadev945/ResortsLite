package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Report base path externalized (injected via REPORT_BASE_PATH env var / ConfigMap)
    @Value("${REPORT_BASE_PATH:reports}")
    private String reportBasePath;

    // cz-java-0070: Local in-memory cache replaced by a shared Amazon ElastiCache (Redis) cache.
    // Connection details come from REDIS_HOST / REDIS_PORT / REDIS_PASSWORD / REDIS_SSL_ENABLED
    // (Kubernetes ConfigMap / Secret). Entries expire after BOOKING_CACHE_TTL_SECONDS.
    private static final String BOOKING_CACHE_PREFIX = "resortslite:booking:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${BOOKING_CACHE_TTL_SECONDS:3600}")
    private long bookingCacheTtlSeconds;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // VIOLATION cr-java-0065 [Cloud Compatibility / Mandatory]: Booking state stored in
        // HTTP session memory. AWS ALB distributes requests across EC2 instances — session
        // data on instance A is invisible to instance B. Auto-scaling and failover breaks.
        // cz-java-0069: HttpSession is externalized via Spring Session backed by Amazon
        // ElastiCache (Redis) - see spring-session-data-redis in pom.xml and spring.session.*
        // / spring.redis.* (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD, REDIS_SSL_ENABLED) properties.
        session.setAttribute("lastBooking", booking); // cr-java-0065
        session.setAttribute("guestName", guestName); // cr-java-0065

        try {
            redisTemplate.opsForValue().set(
                    BOOKING_CACHE_PREFIX + booking.get("bookingId"),
                    objectMapper.writeValueAsString(booking),
                    bookingCacheTtlSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to cache booking", e);
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

        // VIOLATION cr-java-0065 [Cloud Compatibility / Mandatory]: Reading business state
        // from HTTP session — will return null on any other instance in the cluster.
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
        String reportPath = java.nio.file.Paths.get(reportBasePath, month + "_bookings.pdf").toString(); // cz-java-0057

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
