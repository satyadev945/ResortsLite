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

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    // FIXED cz-java-0070 (Line 19): Local Caches
    // Replaced local HashMap cache with Amazon ElastiCache (Redis) for horizontal scaling
    private static final String BOOKING_CACHE_PREFIX = "booking:cache:";

    /**
     * FIXED cz-java-0069 (Lines 34-35): In-Memory Session Storage
     * 
     * Remediation: Externalized session storage to Amazon ElastiCache (Redis) on EKS
     * - HttpSession is now backed by Spring Session with Redis
     * - Session data is stored in Redis instead of in-memory
     * - Enables horizontal scaling across multiple container instances
     * - Session persists across container restarts
     * - Shared session state in EKS cluster environments
     * 
     * Configuration: See RedisSessionConfig.java and application.properties
     * Redis connection via environment variables: REDIS_HOST, REDIS_PORT, REDIS_PASSWORD
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED cz-java-0069: Session data now stored in Redis (not in-memory)
        // Spring Session transparently stores this in ElastiCache Redis
        session.setAttribute("lastBooking", booking);
        session.setAttribute("guestName", guestName);
        
        // FIXED cz-java-0070: Store booking in Redis cache with 1-hour TTL
        String cacheKey = BOOKING_CACHE_PREFIX + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, 1, TimeUnit.HOURS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * FIXED cz-java-0069: Session retrieval now uses Redis-backed session
     * 
     * Session data is retrieved from Redis, not in-memory storage.
     * This works correctly across multiple container instances in EKS.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // FIXED cz-java-0069: Session data retrieved from Redis (not in-memory)
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
        // FIXED czr-java-001: Replaced hardcoded absolute path with environment variable
        // injected via ConfigMap. Path is now configurable per deployment environment.
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
