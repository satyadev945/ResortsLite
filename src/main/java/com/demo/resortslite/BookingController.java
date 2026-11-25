package com.demo.resortslite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cr-java-0067: Booking cache is stored in Amazon ElastiCache for Redis with a TTL, so it is
    // shared across all instances, centrally managed and bounded (entries expire automatically).
    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.cache.booking.key-prefix:resorts:booking:}")
    private String bookingCacheKeyPrefix;

    @Value("${app.cache.booking.ttl-seconds:3600}")
    private long bookingCacheTtlSeconds;

    private void cacheBooking(String bookingId, Map<String, Object> booking) {
        if (bookingId == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(bookingCacheKeyPrefix + bookingId,
                    objectMapper.writeValueAsString(booking), bookingCacheTtlSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            // Cache is best-effort; booking was already persisted by the service
        } catch (RuntimeException e) {
            // Redis unavailable - do not fail the booking request
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

        // cr-java-0065: HttpSession is backed by Spring Session + Amazon ElastiCache for Redis,
        // so session data is shared across all instances behind the AWS ALB (stateless app nodes).
        session.setAttribute("lastBooking", booking);
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

        // cr-java-0065: Session is served from the distributed Redis store (Spring Session),
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
        String reportPath = "/var/legacy/reports/" + month + "_bookings.pdf"; // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
