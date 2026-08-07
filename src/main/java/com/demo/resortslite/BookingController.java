package com.demo.resortslite;

import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final String appConfigConnectionString;
    private final String appConfigEndpoint;
    private final String inventoryEndpointFallback;
    private final long bookingTtlSeconds;

    public BookingController(
            @Value("${app.config.connection-string:}") String appConfigConnectionString,
            @Value("${app.config.endpoint:}") String appConfigEndpoint,
            @Value("${app.inventory.endpoint:https://inventory-svc.internal/rooms}") String inventoryEndpointFallback,
            @Value("${app.redis.booking-ttl-seconds:1800}") long bookingTtlSeconds) {
        this.appConfigConnectionString = appConfigConnectionString;
        this.appConfigEndpoint = appConfigEndpoint;
        this.inventoryEndpointFallback = inventoryEndpointFallback;
        this.bookingTtlSeconds = bookingTtlSeconds;
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        String bookingId = (String) booking.get("bookingId");

        redisTemplate.opsForValue().set(buildBookingKey(bookingId), booking, Duration.ofSeconds(bookingTtlSeconds));
        redisTemplate.opsForValue().set(buildGuestKey(bookingId), guestName, Duration.ofSeconds(bookingTtlSeconds));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        String lastGuest = (String) redisTemplate.opsForValue().get(buildGuestKey(bookingId));

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        String inventoryUrl = resolveInventoryEndpoint() + "/available";

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
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    private String buildBookingKey(String bookingId) {
        return "booking:" + bookingId;
    }

    private String buildGuestKey(String bookingId) {
        return "booking:guest:" + bookingId;
    }

    private String resolveInventoryEndpoint() {
        try {
            if (appConfigConnectionString != null && !appConfigConnectionString.trim().isEmpty()) {
                ConfigurationClient client = new ConfigurationClientBuilder()
                        .connectionString(appConfigConnectionString)
                        .buildClient();
                return client.getConfigurationSetting("app.inventory.endpoint", null).getValue();
            }
            if (appConfigEndpoint != null && !appConfigEndpoint.trim().isEmpty()) {
                ConfigurationClient client = new ConfigurationClientBuilder()
                        .endpoint(appConfigEndpoint)
                        .credential(new DefaultAzureCredentialBuilder().build())
                        .buildClient();
                return client.getConfigurationSetting("app.inventory.endpoint", null).getValue();
            }
        } catch (Exception ignored) {
        }
        return inventoryEndpointFallback;
    }
}
