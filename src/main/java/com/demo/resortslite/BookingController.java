package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import com.demo.resortslite.service.AzureAdAuthenticationService;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // FIXED cr-java-0065: Replaced HttpSession with Redis-backed session storage
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    // FIXED cr-java-0090: Azure AD authentication service for centralized identity management
    @Autowired
    private AzureAdAuthenticationService azureAdAuthService;

    // FIXED cr-java-0071: Externalized inventory service URL to Azure App Configuration
    @Value("${app.inventory.endpoint}")
    private String inventoryServiceUrl;


    /**
     * Create a new booking
     * FIXED cr-java-0065: Session state now stored in Azure Cache for Redis instead of local HTTP session
     * This enables horizontal scaling and stateless architecture
     * FIXED cr-java-0090: Endpoint now requires Azure AD authentication
     */
    @PostMapping("/create")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        
        // FIXED cr-java-0090: Add authenticated user information from Azure AD
        Map<String, String> userInfo = azureAdAuthService.getAuthenticatedUserInfo();
        booking.put("authenticatedUser", userInfo.get("email"));
        booking.put("authenticatedUserId", userInfo.get("userId"));

        // FIXED cr-java-0065: Store booking state in Azure Cache for Redis instead of HTTP session
        // Redis-backed storage enables session sharing across multiple instances
        String bookingId = (String) booking.get("bookingId");
        if (sessionId != null && !sessionId.isEmpty()) {
            String sessionKey = "session:" + sessionId;
            redisTemplate.opsForHash().put(sessionKey, "lastBooking", booking);
            redisTemplate.opsForHash().put(sessionKey, "guestName", guestName);
            // Set session expiration to 30 minutes
            redisTemplate.expire(sessionKey, 30, TimeUnit.MINUTES);
        }

        // FIXED cr-java-0067: Replaced in-memory cache with Azure Cache for Redis with TTL
        // This enables distributed caching across instances and prevents memory exhaustion
        String cacheKey = "booking:cache:" + bookingId;
        redisTemplate.opsForValue().set(cacheKey, booking);
        redisTemplate.expire(cacheKey, 60, TimeUnit.MINUTES); // 60 minutes TTL for booking cache

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Get booking status
     * FIXED cr-java-0065: Retrieve session data from Azure Cache for Redis instead of local HTTP session
     * This ensures session data is available across all instances in the cluster
     * FIXED cr-java-0090: Endpoint now requires Azure AD authentication
     */
    @GetMapping("/status/{bookingId}")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false) String sessionId) {

        // FIXED cr-java-0065: Read session data from Azure Cache for Redis
        // Session data is now shared across all instances, preventing null values on different instances
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            String sessionKey = "session:" + sessionId;
            Object guestObj = redisTemplate.opsForHash().get(sessionKey, "guestName");
            if (guestObj != null) {
                lastGuest = guestObj.toString();
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // FIXED cr-java-0071: Using externalized configuration from Azure App Configuration
        // Previously hard-coded: "http://inventory-service.internal:8081/rooms/available"
        String inventoryUrl = inventoryServiceUrl + "/available";

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
        String reportPath = "/var/legacy/reports/" + month + "_bookings.pdf"; // czr-java-001

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
