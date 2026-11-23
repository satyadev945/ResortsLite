package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;

// cz-java-0063: HttpSession is transparently externalised to Redis by Spring Session
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Report directory externalised via REPORT_BASE_PATH env var (e.g. set from a Kubernetes ConfigMap)
    @Value("${REPORT_BASE_PATH:reports/}")
    private String reportBasePath;

    // cz-java-0070: instance-local HashMap cache replaced by Azure Cache for Redis so the cache is
    // shared across all pods. Connection details (REDIS_HOST/PORT/PASSWORD) are injected from
    // Azure Key Vault via the Secrets Store CSI Driver (Workload Identity) as env vars.
    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${BOOKING_CACHE_PREFIX:resortslite:booking:}")
    private String bookingCachePrefix;

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

        // cz-java-0063: HttpSession is backed by Spring Session + Azure Cache for Redis,
        // so session data is shared across all pods and survives container restarts.
        // cz-java-0069: values stored are serializable (HashMap<String,Object> of Strings / String),
        // as required by the Redis-backed session store.
        session.setAttribute("lastBooking", booking); // cz-java-0069: persisted in Redis via Spring Session
        session.setAttribute("guestName", guestName); // cz-java-0069: persisted in Redis via Spring Session

        try {
            redisTemplate.opsForValue().set(
                    bookingCachePrefix + booking.get("bookingId"),
                    objectMapper.writeValueAsString(booking),
                    bookingCacheTtlSeconds, TimeUnit.SECONDS); // cz-java-0070: shared Redis cache with TTL
        } catch (Exception e) {
            // cache is best-effort; booking already persisted in the database
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

        // cz-java-0063: Session state is read from the Redis-backed Spring Session store.
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
        String base = reportBasePath.endsWith("/") || reportBasePath.endsWith("\\") ? reportBasePath : reportBasePath + "/";
        String reportPath = base + month + "_bookings.pdf"; // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
