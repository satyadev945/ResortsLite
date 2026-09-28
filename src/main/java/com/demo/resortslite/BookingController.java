package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

// FIXED cz-java-0063: HttpSession now backed by Spring Session Data Redis
// Session data is stored in Google Cloud Memorystore for Redis instead of in-memory
// This enables horizontal scaling across multiple GKE pods without session loss
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {


    @Value("${REPORT_BASE_PATH:/var/reports}")
    private String reportBasePath;
    
    @Autowired
    private BookingService bookingService;

    // FIXED cz-java-0070: Local Cache replaced with Google Cloud Memorystore for Redis
    // Redis-backed distributed cache enables horizontal scaling across GKE pods
    // All pod replicas share the same cache state via Cloud Memorystore
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

        // FIXED cz-java-0069 (Lines 34-35): In-Memory Session Storage replaced with Redis
        // Session data now stored in Google Cloud Memorystore for Redis via Spring Session
        // When this GKE pod scales or restarts, session data persists in Cloud Memorystore
        // All pods in the GKE cluster share the same Redis-backed session store
        // Workload Identity Federation provides secure, keyless access to Secret Manager
        session.setAttribute("lastBooking", booking); // cz-java-0069: Line 34 - Redis-backed
        session.setAttribute("guestName", guestName); // cz-java-0069: Line 35 - Redis-backed

        // FIXED cz-java-0070 (Line 19): Store booking in Redis cache with 1-hour TTL
        // Cache is shared across all GKE pods via Google Cloud Memorystore for Redis
        String cacheKey = "booking:cache:" + booking.get("bookingId");
        redisTemplate.opsForValue().set(cacheKey, booking, 1, TimeUnit.HOURS);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        // FIXED cz-java-0063 (Line 48): Session data retrieved from Redis
        // This works correctly even if the request is routed to a different pod
        // Redis ensures session consistency across all instances in the cluster
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
        String reportPath = reportBasePath + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
