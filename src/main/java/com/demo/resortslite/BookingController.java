package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0063: HttpSession is transparently backed by Spring Session + Azure Cache for Redis
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    // cz-java-0057: report directory externalised via REPORT_BASE_PATH env var (ConfigMap-injected)
    @Value("${REPORT_BASE_PATH:reports/}")
    private String reportBasePath;

    @Autowired
    private BookingService bookingService;

    // cz-java-0070: local in-memory cache replaced by Azure Cache for Redis (shared across all replicas).
    // Connection details (REDIS_HOST/REDIS_PORT/REDIS_PASSWORD) are injected from Azure Key Vault via the CSI Driver.
    private static final String BOOKING_CACHE_PREFIX = "resortslite:booking:";

    @Value("${BOOKING_CACHE_TTL_MINUTES:30}")
    private long bookingCacheTtlMinutes;

    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) { // cz-java-0069: session is Redis-backed (Spring Session), not in-memory

        // cz-java-0069: booking is persisted in the Redis-backed session below
        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // VIOLATION cr-java-0065 [Cloud Compatibility / Mandatory]: Booking state stored in
        // HTTP session memory. AWS ALB distributes requests across EC2 instances — session
        // data on instance A is invisible to instance B. Auto-scaling and failover breaks.
        // cz-java-0063: session stored in Redis (Spring Session), not instance memory
        session.setAttribute("lastBooking", new HashMap<>(booking)); // cr-java-0065
        session.setAttribute("guestName", guestName); // cr-java-0065

        // cz-java-0070: cache entry stored in Redis with TTL
        redisTemplate.opsForValue().set(BOOKING_CACHE_PREFIX + booking.get("bookingId"),
                new HashMap<>(booking), bookingCacheTtlMinutes, TimeUnit.MINUTES);

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
        // cz-java-0063: session read from Redis-backed store, shared across all replicas
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
        String reportPath = reportBasePath + month + "_bookings.pdf"; // cz-java-0057

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
