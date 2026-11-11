package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.servlet.http.HttpSession; // cz-java-0063: backed by Spring Session + Redis (ElastiCache)
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @Value("${app.report.base-path:./reports/}")
    private String reportBasePath; // injected via REPORT_BASE_PATH (ConfigMap)

    // cz-java-0070: booking cache is externalized to Amazon ElastiCache (Redis) instead of an
    // instance-local static map, so it is shared across pods. Connection via REDIS_* env vars.
    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.cache.booking-ttl-seconds:3600}")
    private long bookingCacheTtlSeconds;

    private static final String BOOKING_CACHE_PREFIX = "resortslite:booking:";

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069: HTTP session state is externalized via Spring Session backed by Amazon
        // ElastiCache (Redis), so it is shared across pods and survives restarts/scaling on EKS.
        // Stored values (String-valued HashMap, String) are Serializable as required by the Redis session store.
        session.setAttribute("lastBooking", booking); // cz-java-0063: HttpSession is backed by Spring Session (Redis/ElastiCache)
        session.setAttribute("guestName", guestName); // cz-java-0063: HttpSession is backed by Spring Session (Redis/ElastiCache)

        try {
            redisTemplate.opsForValue().set(BOOKING_CACHE_PREFIX + booking.get("bookingId"),
                    objectMapper.writeValueAsString(booking), bookingCacheTtlSeconds, TimeUnit.SECONDS); // cz-java-0070
        } catch (JsonProcessingException e) {
            // cache is best-effort; booking already persisted
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
        String lastGuest = (String) session.getAttribute("guestName"); // cz-java-0063: HttpSession is backed by Spring Session (Redis/ElastiCache)

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
        String reportPath = new java.io.File(reportBasePath, month + "_bookings.pdf").getPath(); // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
