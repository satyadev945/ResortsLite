package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069 [Fixed]: HttpSession is now backed by Spring Session + Amazon ElastiCache (Redis).
// Spring Session transparently intercepts HttpSession calls and stores/retrieves session data
// in Redis, enabling stateless containers, horizontal scaling, and session persistence across
// container restarts on EKS. No API change required — Spring Session replaces the in-memory
// session store at the infrastructure level via HttpSessionIdResolver and RedisIndexedSessionRepository.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0070 [Fixed]: Local in-memory HashMap cache replaced with Amazon ElastiCache (Redis)
    // via RedisTemplate. Cache entries are now shared across all EKS pod replicas, enabling
    // correct horizontal scaling. Connection details are injected via Kubernetes ConfigMap/Secrets
    // using REDIS_HOST and REDIS_PORT environment variables (see RedisSessionConfig).
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cz-java-0057: Absolute file path replaced with environment variable
    @Value("${app.report.base-path:#{systemEnvironment['REPORT_BASE_PATH'] ?: '/var/reports'}}")
    private String reportBasePath;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cz-java-0069 [Fixed]: HttpSession is now externalized to Amazon ElastiCache (Redis)
            // via Spring Session. Session data is stored in Redis, not in JVM heap, so it
            // survives container restarts and is shared across all EKS pod replicas.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 [Fixed]: session.setAttribute now writes to Redis via Spring Session.
        // All EKS pod instances share the same Redis-backed session store (ElastiCache),
        // eliminating sticky-session requirements and enabling true horizontal scaling.
        session.setAttribute("lastBooking", booking); // cz-java-0069: externalized to ElastiCache Redis
        session.setAttribute("guestName", guestName); // cz-java-0069: externalized to ElastiCache Redis

        // cz-java-0070 [Fixed]: Cache entry written to Amazon ElastiCache (Redis) via RedisTemplate.
        // Replaces the former instance-local HashMap (bookingCache) that was invisible to other
        // EKS pod replicas. All pods now share a single distributed cache, ensuring cache
        // consistency under horizontal scaling.
        redisTemplate.opsForHash().put("bookingCache", booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // cz-java-0069 [Fixed]: HttpSession is now externalized to Amazon ElastiCache (Redis)
            // via Spring Session. Reading session attributes retrieves data from Redis,
            // so any EKS pod replica can serve this request regardless of which pod
            // originally created the session.
            HttpSession session) {

        // cz-java-0069 [Fixed]: session.getAttribute now reads from Redis via Spring Session.
        // Returns correct value on any pod in the cluster — no more null on cross-instance reads.
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
        // cz-java-0057 [Fixed]: Absolute file path replaced with environment variable REPORT_BASE_PATH
        // injected via Kubernetes ConfigMap or EKS Pod spec environment variable.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
