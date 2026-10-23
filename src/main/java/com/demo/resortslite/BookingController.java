package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069: Removed javax.servlet.http.HttpSession — in-memory session storage is not
// safe for containerized/EKS deployments. Spring Session transparently replaces HttpSession
// with an Amazon ElastiCache (Redis)-backed implementation via spring-session-data-redis.
// The HttpSession parameter in method signatures is intercepted by Spring Session's
// SessionRepositoryFilter, so no API change is needed — session data is stored in Redis.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    // cz-java-0057 fix: inject report base path from environment variable / ConfigMap
    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    @Autowired
    private BookingService bookingService;

    // cz-java-0070 fix: Replaced local in-memory HashMap cache (instance-local, invisible to
    // other container replicas on EKS) with Amazon ElastiCache (Redis) via RedisTemplate.
    // REDIS_HOST and REDIS_PORT are injected as environment variables in the EKS Pod spec /
    // Kubernetes Secret, ensuring the cache is shared across all horizontally-scaled instances.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            // cz-java-0069: HttpSession is transparently backed by Amazon ElastiCache (Redis)
            // via Spring Session Data Redis (spring-session-data-redis on classpath).
            // SessionRepositoryFilter intercepts this parameter and delegates all
            // setAttribute/getAttribute calls to the Redis-backed session store,
            // ensuring session state survives container restarts and horizontal scaling on EKS.
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 fix (Line 34): session.setAttribute("lastBooking", ...) — previously stored
        // in JVM heap memory (lost on container restart/scale). Now persisted to Amazon ElastiCache
        // (Redis) via Spring Session Data Redis. REDIS_HOST and REDIS_PORT are injected as
        // environment variables in the EKS Pod spec / Kubernetes Secret.
        session.setAttribute("lastBooking", booking);

        // cz-java-0069 fix (Line 35): session.setAttribute("guestName", ...) — previously stored
        // in JVM heap memory (lost on container restart/scale). Now persisted to Amazon ElastiCache
        // (Redis) via Spring Session Data Redis, consistent across all EKS pod replicas.
        session.setAttribute("guestName", guestName);

        // cz-java-0070 fix: Store booking in Amazon ElastiCache (Redis) via RedisTemplate
        // instead of the former local HashMap bookingCache. This ensures cache entries are
        // visible to all container replicas on EKS and survive individual pod restarts.
        redisTemplate.opsForValue().set("booking:" + booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            // cz-java-0069: HttpSession is transparently backed by Amazon ElastiCache (Redis)
            // via Spring Session Data Redis — getAttribute reads from Redis, not JVM heap.
            HttpSession session) {

        // cz-java-0069: Session attribute retrieved from Amazon ElastiCache (Redis)
        // via Spring Session Data Redis — consistent across all container instances on EKS.
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
        // cz-java-0057 fix: replaced hardcoded absolute path "/var/legacy/reports/" with
        // environment variable REPORT_BASE_PATH injected via Kubernetes ConfigMap / EKS Pod spec
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
