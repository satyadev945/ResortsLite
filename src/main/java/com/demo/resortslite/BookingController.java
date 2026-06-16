package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import javax.annotation.PostConstruct;
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

    @Value("${aws.region}")
    private String awsRegion;

    @Value("${aws.ssm.parameter.inventory.endpoint}")
    private String inventoryEndpointParameterName;

    @Value("${app.inventory.endpoint}")
    private String inventoryEndpoint;

    @Value("${spring.cache.redis.time-to-live:3600000}")
    private long cacheTtlMillis;

    private String inventoryServiceUrl;

    @PostConstruct
    public void init() {
        // Load inventory service URL from AWS Systems Manager Parameter Store
        loadInventoryUrlFromParameterStore();
    }

    /**
     * Loads inventory service URL from AWS Systems Manager Parameter Store.
     * Replaces hard-coded environment URLs (blocker-10: cr-java-0071)
     */
    private void loadInventoryUrlFromParameterStore() {
        try {
            SsmClient ssmClient = SsmClient.builder()
                    .region(software.amazon.awssdk.regions.Region.of(awsRegion))
                    .build();

            GetParameterRequest parameterRequest = GetParameterRequest.builder()
                    .name(inventoryEndpointParameterName)
                    .withDecryption(true)
                    .build();

            GetParameterResponse parameterResponse = ssmClient.getParameter(parameterRequest);
            this.inventoryServiceUrl = parameterResponse.parameter().value();

            ssmClient.close();
        } catch (Exception e) {
            // Fallback to application.properties value
            this.inventoryServiceUrl = inventoryEndpoint;
            System.err.println("Warning: Could not load inventory URL from Parameter Store, using default: " + e.getMessage());
        }
    }

    /**
     * Creates a booking and stores state in Redis instead of HTTP session.
     * Replaces HTTP session storage (blockers 13-16: cr-java-0065)
     * Replaces in-memory cache with Redis (blocker-20: cr-java-0067)
     */
    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        String bookingId = (String) booking.get("bookingId");

        // Store booking in Redis with TTL instead of in-memory cache
        String cacheKey = "booking:" + bookingId;
        redisTemplate.opsForValue().set(cacheKey, booking, cacheTtlMillis, TimeUnit.MILLISECONDS);

        // Store session data in Redis instead of HTTP session
        if (sessionId != null && !sessionId.isEmpty()) {
            String sessionKey = "session:" + sessionId + ":lastBooking";
            String guestKey = "session:" + sessionId + ":guestName";
            
            redisTemplate.opsForValue().set(sessionKey, booking, 30, TimeUnit.MINUTES);
            redisTemplate.opsForValue().set(guestKey, guestName, 30, TimeUnit.MINUTES);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    /**
     * Retrieves booking status from Redis instead of HTTP session.
     * Replaces HTTP session storage (blockers 13-16: cr-java-0065)
     */
    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestParam(required = false) String sessionId) {

        // Retrieve guest name from Redis instead of HTTP session
        String lastGuest = null;
        if (sessionId != null && !sessionId.isEmpty()) {
            String guestKey = "session:" + sessionId + ":guestName";
            lastGuest = (String) redisTemplate.opsForValue().get(guestKey);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    /**
     * Checks room availability using externalized inventory service URL.
     * Replaces hard-coded environment URLs (blocker-10: cr-java-0071)
     * Uses HTTPS for cloud security compliance
     */
    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Use HTTPS and externalized URL from Parameter Store
        String inventoryUrl = inventoryServiceUrl + "/rooms/available";

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    /**
     * Downloads report - no longer uses hard-coded file paths.
     * File operations are now handled by ReportService using S3
     */
    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        Map<String, Object> response = new HashMap<>();
        response.put("message", bookingService.generateReport(month));
        response.put("note", "Reports are now stored in S3 - use ReportService for generation");
        return response;
    }
}
