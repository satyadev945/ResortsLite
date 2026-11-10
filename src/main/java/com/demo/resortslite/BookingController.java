package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Report directory externalized via REPORT_BASE_PATH env var (ConfigMap)
    @Value("${app.report.base-path}")
    private String reportBasePath;

    // cz-java-0070: Local in-process cache replaced by Azure Cache for Redis (shared across pods, with TTL).
    // Connection settings (REDIS_HOST/PORT/PASSWORD) are injected from Azure Key Vault via the Secrets Store CSI Driver.
    private static final String BOOKING_CACHE_PREFIX = "resortslite:booking:";

    @Value("${app.cache.booking-ttl-seconds:3600}")
    private long bookingCacheTtlSeconds;

    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0063: HttpSession is backed by Spring Session + Azure Cache for Redis, so session
        // data is shared across all pods. Stored values must be Serializable.
        session.setAttribute("lastBooking", new HashMap<>(booking)); // cz-java-0069: Redis-backed Spring Session (HashMap is Serializable)
        session.setAttribute("guestName", guestName); // cz-java-0069: Redis-backed Spring Session

        redisTemplate.opsForValue().set(BOOKING_CACHE_PREFIX + booking.get("bookingId"),
                new HashMap<>(booking), bookingCacheTtlSeconds, TimeUnit.SECONDS); // cz-java-0070

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
        String lastGuest = (String) session.getAttribute("guestName"); // cz-java-0063

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
        String reportPath = Paths.get(reportBasePath, month + "_bookings.pdf").toString(); // externalized (cz-java-0057)

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
