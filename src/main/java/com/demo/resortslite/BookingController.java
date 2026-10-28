package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069: javax.servlet.http.HttpSession is now transparently backed by Spring Session
// + Amazon ElastiCache (Redis) via @EnableRedisHttpSession in RedisSessionConfig.
// Session data is stored externally in Redis, not in JVM heap memory, enabling stateless
// horizontal scaling on EKS — any pod can serve any request without sticky sessions.
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0057: Absolute file path externalized to environment variable REPORT_BASE_PATH
    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    // cz-java-0070 FIXED: Replaced instance-local HashMap cache with Amazon ElastiCache (Redis)
    // via RedisTemplate. Cache entries are now shared across all EKS pod replicas, enabling
    // correct horizontal scaling. REDIS_HOST and REDIS_PORT are injected via EKS ConfigMap/Secrets.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate; // cz-java-0070 fixed: Redis-backed distributed cache

    // cz-java-0069: HttpSession is now backed by Spring Session + Amazon ElastiCache (Redis).
    // Session data is stored externally in Redis, enabling stateless horizontal scaling on EKS.
    // @EnableRedisHttpSession in RedisSessionConfig activates the Redis session repository;
    // all HttpSession calls below are transparently routed to ElastiCache — no code change needed
    // at the call sites beyond ensuring the Spring Session infrastructure is wired correctly.
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) { // cz-java-0069: session now persisted in Amazon ElastiCache (Redis) via Spring Session

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 FIXED: Session attributes stored in Amazon ElastiCache (Redis) via Spring Session.
        // AWS ALB sticky sessions are no longer required — any EKS pod can serve subsequent requests.
        // REDIS_HOST and REDIS_PORT are injected via EKS ConfigMap/Secrets Manager (see RedisSessionConfig).
        session.setAttribute("lastBooking", booking); // cz-java-0069 fixed: Redis-backed via Spring Session + ElastiCache
        session.setAttribute("guestName", guestName); // cz-java-0069 fixed: Redis-backed via Spring Session + ElastiCache

        // cz-java-0070 FIXED: Cache entry stored in Amazon ElastiCache (Redis) via RedisTemplate.
        // Replaces the former instance-local HashMap — all EKS pods share the same distributed cache.
        redisTemplate.opsForValue().set((String) booking.get("bookingId"), booking); // cz-java-0070 fixed

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    // cz-java-0069: HttpSession is now backed by Spring Session + Amazon ElastiCache (Redis).
    // Reading session attributes from Redis ensures consistency across all EKS pod replicas.
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) { // cz-java-0069: session now persisted in Amazon ElastiCache (Redis) via Spring Session

        // cz-java-0069 FIXED: Session attribute read from Amazon ElastiCache (Redis) — consistent
        // across all instances in the EKS cluster regardless of which pod handles the request.
        String lastGuest = (String) session.getAttribute("guestName"); // cz-java-0069 fixed: Redis-backed via Spring Session

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
        // cz-java-0057 [Software Portability / Mandatory]: Replaced hardcoded absolute
        // file path with environment variable REPORT_BASE_PATH injected via EKS ConfigMap.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf"; // cz-java-0057 fixed

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
