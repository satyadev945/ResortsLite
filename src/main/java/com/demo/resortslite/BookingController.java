package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private AzureConfigurationService configurationService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${app.cache.booking-ttl-seconds:3600}")
    private long bookingCacheTtlSeconds;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        String bookingId = (String) booking.get("bookingId");

        cacheBooking(bookingId, booking);
        cacheGuestForBooking(bookingId, guestName);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        response.put("stateStore", "Azure Cache for Redis");
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        String lastGuest = getCachedGuestForBooking(bookingId);
        Object cachedBooking = redisTemplate.opsForValue().get(bookingKey(bookingId));

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("cacheStore", "Azure Cache for Redis");
        result.put("cachedDetails", cachedBooking);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        String inventoryUrl = configurationService.getString(
                "app:inventory:endpoint",
                "app.inventory.endpoint",
                "https://inventory.example.invalid/rooms/available");

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        String reportName = month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", configurationService.getString(
                "app:report:download-base-url",
                "app.report.download-base-url",
                "https://reports.example.invalid/download/") + reportName);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    private void cacheBooking(String bookingId, Map<String, Object> booking) {
        redisTemplate.opsForValue().set(bookingKey(bookingId), booking, bookingCacheTtlSeconds, TimeUnit.SECONDS);
    }

    private void cacheGuestForBooking(String bookingId, String guestName) {
        redisTemplate.opsForValue().set(guestKey(bookingId), guestName, bookingCacheTtlSeconds, TimeUnit.SECONDS);
    }

    private String getCachedGuestForBooking(String bookingId) {
        Object value = redisTemplate.opsForValue().get(guestKey(bookingId));
        return value == null ? null : value.toString();
    }

    private String bookingKey(String bookingId) {
        return "booking:" + bookingId;
    }

    private String guestKey(String bookingId) {
        return "booking:guest:" + bookingId;
    }
}
