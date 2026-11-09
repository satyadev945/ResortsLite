package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate; // cz-java-0070: RedisTemplate for Amazon ElastiCache (Redis) distributed cache
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession; // cz-java-0069: HttpSession now backed by Spring Session Redis (ElastiCache) via RedisSessionConfig
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // cz-java-0070: Replaced local in-memory HashMap cache with Amazon ElastiCache (Redis) via RedisTemplate.
    // RedisTemplate is backed by the LettuceConnectionFactory configured in RedisSessionConfig,
    // pointing to the ElastiCache endpoint injected via REDIS_HOST / REDIS_PORT environment variables.
    // This ensures the cache is shared across all horizontally-scaled EKS pod replicas.
    @Autowired
    private RedisTemplate<String, Object> redisTemplate; // cz-java-0070: distributed cache via Amazon ElastiCache (Redis)

    @Value("${REPORT_BASE_PATH:/tmp/reports/}")
    private String reportBasePath;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) { // cz-java-0069: HttpSession is externalized to Amazon ElastiCache (Redis) via Spring Session (RedisSessionConfig)

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // cz-java-0069: Session attributes are stored in Amazon ElastiCache (Redis) via Spring Session
        // — safe for horizontal scaling and container restarts on EKS; no in-memory session loss on restart
        session.setAttribute("lastBooking", booking); // cz-java-0069: persisted to Amazon ElastiCache (Redis)
        session.setAttribute("guestName", guestName); // cz-java-0069: persisted to Amazon ElastiCache (Redis)

        // cz-java-0070: Cache booking in Amazon ElastiCache (Redis) instead of local HashMap.
        // opsForHash() stores the booking map under the key "bookingCache:<bookingId>",
        // visible to all EKS pod replicas — eliminates instance-local cache inconsistency.
        redisTemplate.opsForHash().putAll("bookingCache:" + booking.get("bookingId"), booking); // cz-java-0070

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) { // cz-java-0069: HttpSession is externalized to Amazon ElastiCache (Redis) via Spring Session (RedisSessionConfig)

        // cz-java-0069: Session attribute read from Amazon ElastiCache (Redis) —
        // consistent across all container instances in the EKS cluster
        String lastGuest = (String) session.getAttribute("guestName"); // cz-java-0069: read from Amazon ElastiCache (Redis)

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
        String reportPath = reportBasePath + month + "_bookings.pdf"; // cz-java-0057: replaced hardcoded path with env var REPORT_BASE_PATH

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
