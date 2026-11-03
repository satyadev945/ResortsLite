package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069 [FIXED]: Replaced in-memory HttpSession with Spring Session backed by
// Azure Cache for Redis on AKS, using the Azure Key Vault CSI Driver with Workload
// Identity to securely inject Redis credentials (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD).
// The javax.servlet.http.HttpSession interface is preserved so Spring Session transparently
// serialises session data to Redis, enabling stateless horizontal scaling across AKS pods
// without sticky sessions. Session data survives container restarts and scale-out events.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0070 [FIXED]: Replaced local in-memory HashMap cache with Azure Cache for
    // Redis via RedisTemplate. The local static Map was instance-local and invisible to
    // other AKS pod replicas, breaking horizontal scaling. RedisTemplate connects to the
    // shared Azure Cache for Redis instance (credentials injected via Azure Key Vault CSI
    // Driver on AKS), so all pods share the same distributed cache with a configurable TTL.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cz-java-0070 [FIXED]: Cache TTL injected via environment variable / ConfigMap.
    // Defaults to 3600 seconds (1 hour). Override with BOOKING_CACHE_TTL_SECONDS env var.
    @Value("${BOOKING_CACHE_TTL_SECONDS:3600}")
    private long bookingCacheTtlSeconds;

    // cz-java-0057: Externalized absolute file path via environment variable / ConfigMap
    @Value("${app.report.base-path:#{systemEnvironment['REPORT_BASE_PATH'] ?: '/var/reports'}}")
    private String reportBasePath;

    // cz-java-0069 [FIXED]: HttpSession parameter is now backed by Spring Session + Redis
    // (configured via RedisSessionConfig). Session data is stored in Azure Cache for Redis,
    // injected through Azure Key Vault CSI Driver on AKS, so all pods share session state.
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 [FIXED] Lines 34-35: Session attributes are now persisted to
        // Azure Cache for Redis via Spring Session (RedisSessionConfig + @EnableRedisHttpSession).
        // Any AKS pod can read this session data on subsequent requests, eliminating the
        // in-memory session storage that was lost on container restart or scale-out.
        session.setAttribute("lastBooking", booking); // cz-java-0069 - Redis-backed via Spring Session
        session.setAttribute("guestName", guestName); // cz-java-0069 - Redis-backed via Spring Session

        // cz-java-0070 [FIXED]: Store booking in Azure Cache for Redis with TTL instead of
        // the former local static HashMap. RedisTemplate.opsForValue().set() writes the
        // entry to the shared Redis instance so all AKS pod replicas can read it.
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    // cz-java-0069 [FIXED]: HttpSession parameter is now backed by Spring Session + Redis.
    // Reading session attributes works correctly across all AKS pod replicas.
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cz-java-0069 [FIXED]: Session attribute is retrieved from Azure Cache for Redis
        // via Spring Session — consistent across all instances in the AKS cluster.
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
        // cz-java-0057 [FIXED]: Absolute file path replaced with environment variable
        // injected via Kubernetes ConfigMap / Azure App Configuration.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
