package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Inventory service URL is externalized and resolved from Azure App Configuration
    // (key: app.inventory.availability-url), with environment variable fallback.
    @Value("${app.inventory.availability-url:}")
    private String inventoryUrl;

    // cr-java-0067: The instance-local static HashMap cache was replaced by Azure Cache for Redis
    // (spring.redis.* in application.properties). Entries are written with a TTL so memory is
    // bounded and the cache is shared consistently across all instances.
    private static final String BOOKING_CACHE_PREFIX = "resortslite:booking:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.cache.booking-ttl:PT30M}")
    private Duration bookingCacheTtl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cr-java-0065: HttpSession is backed by Spring Session + Azure Cache for Redis
        // (see spring.session.* / spring.redis.* in application.properties), so session data
        // is externalized and shared across all instances (stateless application tier).
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);

        cacheBooking((String) booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    private void cacheBooking(String bookingId, Map<String, Object> booking) {
        if (bookingId == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    BOOKING_CACHE_PREFIX + bookingId,
                    objectMapper.writeValueAsString(booking),
                    bookingCacheTtl);
        } catch (JsonProcessingException | RuntimeException e) {
            // Cache is best-effort; a cache failure must not fail the booking request.
        }
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cr-java-0065: Session is stored in Azure Cache for Redis, readable from any instance.
        String lastGuest = (String) session.getAttribute("guestName");

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
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
