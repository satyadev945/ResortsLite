package com.demo.resortslite;

import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${azure.appconfig.connection-string:}")
    private String appConfigConnectionString;

    @Value("${app.inventory.endpoint:https://inventory-svc.internal/rooms/available}")
    private String configuredInventoryEndpoint;

    private ConfigurationClient configurationClient;
    private String inventoryUrl;

    @PostConstruct
    public void initializeConfiguration() {
        if (appConfigConnectionString != null && !appConfigConnectionString.trim().isEmpty()) {
            configurationClient = new ConfigurationClientBuilder()
                    .connectionString(appConfigConnectionString)
                    .buildClient();
        }
        inventoryUrl = resolveConfiguration("app.inventory.endpoint", configuredInventoryEndpoint);
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        String effectiveSessionId = sessionId != null && !sessionId.trim().isEmpty()
                ? sessionId
                : (String) booking.get("bookingId");

        redisTemplate.opsForValue().set(redisKey(effectiveSessionId, "lastBooking"), booking.toString(), Duration.ofMinutes(30));
        redisTemplate.opsForValue().set(redisKey(effectiveSessionId, "guestName"), guestName, Duration.ofMinutes(30));
        redisTemplate.opsForValue().set(redisKey("booking-cache", (String) booking.get("bookingId")), booking.toString(), Duration.ofMinutes(15));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        response.put("sessionId", effectiveSessionId);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {

        String lastGuest = sessionId == null ? null : redisTemplate.opsForValue().get(redisKey(sessionId, "guestName"));

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", "azure-blob://reports/" + month + "_bookings.pdf");
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    private String resolveConfiguration(String key, String fallback) {
        if (configurationClient != null) {
            try {
                return configurationClient.getConfigurationSetting(key, null).getValue();
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    private String redisKey(String prefix, String suffix) {
        return "resortslite:" + prefix + ":" + suffix;
    }
}
