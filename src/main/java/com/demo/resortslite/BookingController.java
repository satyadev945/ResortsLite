package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069: HttpSession is now transparently backed by Amazon ElastiCache (Redis)
// via Spring Session (@EnableRedisHttpSession in RedisSessionConfig). The standard
// javax.servlet.http.HttpSession API is preserved; Spring Session replaces the in-memory
// store with a shared Redis store, making sessions safe across container restarts and
// horizontal scaling on EKS.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Value("${REPORT_BASE_PATH:/var/legacy/reports/}")
    private String reportBasePath;

    @Autowired
    private BookingService bookingService;

    // cz-java-0070: Replaced local in-process HashMap cache with Amazon ElastiCache (Redis)
    // via RedisTemplate. The shared Redis store is visible to all container replicas,
    // eliminating instance-local cache inconsistency during horizontal scaling on EKS.
    // Connection details are injected via environment variables (REDIS_HOST, REDIS_PORT,
    // REDIS_PASSWORD) mapped through Kubernetes ConfigMaps and Secrets with IRSA-secured access.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // cz-java-0069: HttpSession parameter is now backed by Amazon ElastiCache (Redis)
    // via Spring Session. Session attributes written here are stored in Redis and are visible
    // to all container replicas, eliminating the per-instance session isolation problem.
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 (line 34): session.setAttribute now persists to Amazon ElastiCache (Redis)
        // via Spring Session — safe for horizontal scaling and container restarts on EKS.
        // In-memory session storage replaced with externalized Redis-backed session store.
        session.setAttribute("lastBooking", booking); // cz-java-0069: externalized to ElastiCache (Redis) via Spring Session
        // cz-java-0069 (line 35): session.setAttribute now persists to Amazon ElastiCache (Redis)
        // via Spring Session — guestName is stored in the shared Redis session store.
        session.setAttribute("guestName", guestName); // cz-java-0069: externalized to ElastiCache (Redis) via Spring Session

        // cz-java-0070: Cache entry stored in Amazon ElastiCache (Redis) via RedisTemplate.
        // Replaces the former instance-local HashMap; all EKS replicas share the same cache.
        redisTemplate.opsForValue().set("booking:" + booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    // cz-java-0069: HttpSession parameter is now backed by Amazon ElastiCache (Redis)
    // via Spring Session. Reading session attributes here retrieves data from the shared Redis
    // store, so any container replica in the EKS cluster can serve this request correctly.
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cz-java-0069: Session read is now served from Redis (ElastiCache) — consistent
        // across all instances in the cluster.
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
        String reportPath = reportBasePath + month + "_bookings.pdf"; // cz-java-0057: replaced hardcoded absolute path with environment variable

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
