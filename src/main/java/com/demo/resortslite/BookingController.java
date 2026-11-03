package com.demo.resortslite;

import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private static final String DEFAULT_INVENTORY_URL = "https://inventory-service.internal:8081/rooms/available";
    private static final String SESSION_LAST_BOOKING_KEY = "lastBooking";
    private static final String SESSION_GUEST_NAME_KEY = "guestName";

    @Autowired
    private BookingService bookingService;

    private static final Duration BOOKING_CACHE_TTL = Duration.ofMinutes(30);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Externalize session state to Redis-backed Spring Session so booking state is shared
        // across instances and remains available during horizontal scaling in Azure.
        bookingService.storeSessionAttribute(SESSION_LAST_BOOKING_KEY, booking);
        bookingService.storeSessionAttribute(SESSION_GUEST_NAME_KEY, guestName);

        ValueOperations<String, String> valueOperations = redisTemplate.opsForValue();
        valueOperations.set(buildBookingCacheKey((String) booking.get("bookingId")), booking.toString(), BOOKING_CACHE_TTL);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {

        // Read business state from Redis-backed Spring Session storage instead of servlet
        // container memory to support stateless scaling on Azure.
        String lastGuest = bookingService.getSessionAttribute(SESSION_GUEST_NAME_KEY, String.class);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        String inventoryUrl = getConfiguredValue("inventory.service.url", DEFAULT_INVENTORY_URL);

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        String reportPath = bookingService.generateReport(month);

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", "Report generation triggered");
        return response;
    }

    @GetMapping("/me")
    public Map<String, Object> currentUser(Authentication authentication) {
        return bookingService.getAuthenticatedUserProfile(authentication);
    }

    private String getConfiguredValue(String key, String defaultValue) {
        String connectionString = System.getenv("AZURE_APP_CONFIGURATION_CONNECTION_STRING");
        if (connectionString == null || connectionString.trim().isEmpty()) {
            return defaultValue;
        }

        try {
            ConfigurationClient configurationClient = new ConfigurationClientBuilder()
                    .connectionString(connectionString)
                    .buildClient();
            String configuredValue = configurationClient.getConfigurationSetting(key, null).getValue();
            return (configuredValue == null || configuredValue.trim().isEmpty()) ? defaultValue : configuredValue;
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }

    private String buildBookingCacheKey(String bookingId) {
        return "booking:cache:" + bookingId;
    }
}
