package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    // Booking/guest state is held in Google Cloud Memorystore for Redis instead of the
    // instance-local HTTP session, so the application stays stateless across instances.
    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.state.ttl-minutes:30}")
    private long stateTtlMinutes;

    private static final String LAST_BOOKING_KEY = "resorts:booking:last:";
    private static final String GUEST_NAME_KEY = "resorts:booking:guest:";

    // Inventory service endpoint externalised via environment variable INVENTORY_SERVICE_URL
    // (can be sourced from GCP Secret Manager via sm:// placeholder in application.properties).
    @Value("${app.inventory.availability-url:${INVENTORY_SERVICE_URL:https://inventory-service.internal:8081/rooms/available}}")
    private String inventoryUrl;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        // Booking state is stored in Memorystore for Redis (keyed by bookingId, with TTL)
        // rather than in HTTP session memory.
        String bookingId = String.valueOf(booking.get("bookingId"));
        Duration ttl = Duration.ofMinutes(stateTtlMinutes);
        try {
            redisTemplate.opsForValue().set(LAST_BOOKING_KEY + bookingId, objectMapper.writeValueAsString(booking), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize booking state for Redis", e);
        }
        redisTemplate.opsForValue().set(GUEST_NAME_KEY + bookingId, guestName, ttl);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId) {

        // Read guest state from Memorystore for Redis (shared across all instances).
        String lastGuest = redisTemplate.opsForValue().get(GUEST_NAME_KEY + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        // Inventory endpoint is injected from externalised configuration (inventoryUrl field).

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
