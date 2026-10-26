package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// cz-java-0069 (Lines 34-35): javax.servlet.http.HttpSession is retained; Spring Session
// transparently replaces the in-memory session store with Amazon ElastiCache (Redis) on EKS
// via @EnableRedisHttpSession on ResortsLiteApplication and spring-session-data-redis dependency.
// Session data is no longer lost on container restart or scale-out — all session.setAttribute /
// session.getAttribute calls are automatically routed to the external Redis store, ensuring
// consistent user experience across all EKS pod replicas (IRSA used for secure ElastiCache access).
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0057: Externalized absolute file path to environment variable
    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;

    // cz-java-0070: Replaced local in-memory HashMap cache with Amazon ElastiCache (Redis)
    // via RedisTemplate. The previous static HashMap was instance-local and invisible to other
    // EKS pod replicas, breaking horizontal scaling. RedisTemplate routes all cache reads/writes
    // to the shared ElastiCache (Redis) cluster, ensuring cache consistency across all pods.
    // Connection details are injected via REDIS_HOST / REDIS_PORT environment variables
    // (backed by Kubernetes ConfigMaps and Secrets with IRSA-secured access).
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069 (Line 34): Externalized to Amazon ElastiCache (Redis) via Spring Session.
        // Previously, this in-memory session attribute was lost on container restart or when the
        // load balancer routed the next request to a different EKS pod. With @EnableRedisHttpSession
        // and spring-session-data-redis, this setAttribute call is transparently persisted to Redis,
        // making session data durable and available across all pod replicas in the EKS cluster.
        session.setAttribute("lastBooking", booking); // cz-java-0069: now backed by ElastiCache Redis
        // cz-java-0069 (Line 35): Same Redis-backed externalisation — guestName session attribute
        // is stored in Amazon ElastiCache (Redis) and survives container restarts and horizontal scaling.
        session.setAttribute("guestName", guestName); // cz-java-0069: now backed by ElastiCache Redis

        // cz-java-0070: Cache booking in Amazon ElastiCache (Redis) via RedisTemplate.
        // Replaces the former static HashMap bookingCache which was instance-local and
        // caused cache misses when requests were routed to a different EKS pod replica.
        redisTemplate.opsForValue().set("booking:" + booking.get("bookingId"), booking);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // cz-java-0069: Session attribute is read from Amazon ElastiCache (Redis) via Spring Session.
        // Any EKS pod in the cluster can retrieve this value regardless of which pod originally stored
        // it, eliminating the horizontal-scaling session loss issue caused by in-memory session storage.
        String lastGuest = (String) session.getAttribute("guestName"); // cz-java-0069: reads from Redis

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
        // cz-java-0057: Replaced hardcoded absolute file path with environment variable REPORT_BASE_PATH
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
