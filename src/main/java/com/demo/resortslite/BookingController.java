package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
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

    // Injected from environment variable / Kubernetes ConfigMap (cz-java-0057)
    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    // cz-java-0070: Replaced local in-process HashMap cache with Amazon ElastiCache (Redis)
    // via RedisTemplate. Connection details (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD) are
    // injected through Kubernetes ConfigMaps and Secrets, secured with IRSA on EKS.
    // This ensures the cache is shared across all horizontally scaled pod replicas and
    // survives individual container restarts, eliminating instance-local cache inconsistency.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cz-java-0069: HttpSession is now backed by Spring Session + Amazon ElastiCache (Redis).
    // In-memory session storage is replaced with an external Redis store on Amazon ElastiCache,
    // so session data survives container restarts and is shared across all EKS pod replicas.
    // The standard HttpSession API is preserved — no application-layer API changes required.
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 [FIX - Lines 34-35]: In-memory session storage replaced with
        // Amazon ElastiCache (Redis) via Spring Session. Session attributes written here
        // are persisted to the external Redis store, not to JVM heap memory. All EKS pod
        // replicas share the same Redis-backed session store, eliminating instance-local
        // state loss on container restart or horizontal scale-out.
        session.setAttribute("lastBooking", booking); // cz-java-0069: Redis-backed via Spring Session
        session.setAttribute("guestName", guestName); // cz-java-0069: Redis-backed via Spring Session

        // cz-java-0070 [FIX - Line 19]: Replaced local HashMap bookingCache with
        // Amazon ElastiCache (Redis) via RedisTemplate. Cache entries are stored in the
        // shared Redis cluster with a 30-minute TTL, visible to all EKS pod replicas.
        String cacheKey = "booking:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, 30, TimeUnit.MINUTES);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cz-java-0069 [FIX]: Session attribute read is now served from Amazon ElastiCache
        // (Redis) via Spring Session. Any EKS pod in the cluster can resolve this session
        // attribute, ensuring consistent state across horizontally scaled instances.
        String lastGuest = (String) session.getAttribute("guestName"); // cz-java-0069: Redis-backed

        // cz-java-0070 [FIX]: Booking lookup now reads from Amazon ElastiCache (Redis)
        // via RedisTemplate. Cache hit returns the shared distributed entry; on miss,
        // falls back to the BookingService (database) to retrieve the booking details.
        String cacheKey = "booking:" + bookingId;
        @SuppressWarnings("unchecked")
        Map<String, Object> cachedBooking = (Map<String, Object>) redisTemplate.opsForValue().get(cacheKey);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
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
        // cz-java-0057: Replaced hardcoded absolute path with environment variable injected
        // via Kubernetes ConfigMap. REPORT_BASE_PATH is set in the EKS Pod spec.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
