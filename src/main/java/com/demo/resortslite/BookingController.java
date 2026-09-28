package com.demo.resortslite;

import net.spy.memcached.MemcachedClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {


    @Value("${app.report.path:/mnt/efs/reports}")
    private String reportBasePath;
    
    @Value("${memcached.expiration:3600}")
    private int cacheExpiration;
    
    @Autowired
    private BookingService bookingService;

    @Autowired
    private JwtTokenService jwtTokenService;

    // FIXED cz-java-0070: Replaced local in-memory cache with AWS ElastiCache Memcached
    // This enables horizontal scaling across ECS Fargate tasks without cache inconsistency.
    // The Memcached client connects to a distributed cache cluster, ensuring all container
    // instances share the same cache data. Cache endpoint is injected via environment variable
    // from AWS SSM Parameter Store.
    @Autowired
    private MemcachedClient memcachedClient;

    /**
     * TRANSITIONAL STRATEGY (cz-java-0069): ALB Session Affinity Configuration Required
     * 
     * While this application has been refactored to use stateless JWT authentication,
     * as a low-effort transitional measure for ECS Fargate deployment, enable ALB 
     * target group stickiness to minimize session disruption during the migration period.
     * 
     * AWS Configuration Required:
     * - Enable stickiness on the ALB target group
     * - Set stickiness type to: lb_cookie (Application Load Balancer generated cookie)
     * - Set stickiness duration: 3600 seconds (1 hour) to match JWT expiration
     * 
     * Terraform Example:
     * resource "aws_lb_target_group" "resorts_lite" {
     *   stickiness {
     *     enabled         = true
     *     type            = "lb_cookie"
     *     cookie_duration = 3600
     *   }
     * }
     * 
     * AWS CLI Example:
     * aws elbv2 modify-target-group-attributes \
     *   --target-group-arn <target-group-arn> \
     *   --attributes Key=stickiness.enabled,Value=true \
     *                Key=stickiness.type,Value=lb_cookie \
     *                Key=stickiness.lb_cookie.duration_seconds,Value=3600
     *
     * This ensures that during the transitional period, requests from the same client
     * are routed to the same ECS task, reducing the impact of any remaining stateful
     * behavior while the full stateless migration is validated.
     *
     * FIXED cz-java-0063: Replaced HttpSession with stateless JWT authentication.
     * JWT tokens are signed with secret from AWS Secrets Manager and returned to client.
     * This enables horizontal scaling across ECS Fargate tasks without session affinity.
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // FIXED cz-java-0063: Generate JWT token with booking data instead of storing in session
        Map<String, Object> tokenClaims = new HashMap<>();
        tokenClaims.put("lastBooking", booking);
        tokenClaims.put("guestName", guestName);
        
        String jwtToken = jwtTokenService.generateToken(guestName, tokenClaims);

        // FIXED cz-java-0070: Store booking in distributed Memcached cache instead of local HashMap
        // This allows all ECS Fargate tasks to access the same cached booking data
        String bookingId = (String) booking.get("bookingId");
        try {
            memcachedClient.set(bookingId, cacheExpiration, booking);
        } catch (Exception e) {
            // Log error but don't fail the request if cache is unavailable
            System.err.println("Failed to cache booking in Memcached: " + e.getMessage());
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        response.put("token", jwtToken); // Return JWT token to client for subsequent requests
        return response;
    }

    /**
     * FIXED cz-java-0063: Replaced HttpSession with JWT token validation.
     * Client must provide JWT token in Authorization header for stateless authentication.
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        // FIXED cz-java-0063: Extract guest name from JWT token instead of session
        String lastGuest = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtTokenService.isTokenValid(token)) {
                lastGuest = jwtTokenService.extractGuestName(token);
            }
        }

        // FIXED cz-java-0070: Retrieve booking from distributed Memcached cache
        Object cachedBooking = null;
        try {
            cachedBooking = memcachedClient.get(bookingId);
        } catch (Exception e) {
            System.err.println("Failed to retrieve booking from Memcached: " + e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("cachedData", cachedBooking);
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
