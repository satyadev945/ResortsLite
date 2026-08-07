package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    private final StringRedisTemplate redisTemplate;
    private final String inventoryEndpoint;
    private final String reportDownloadBaseUrl;
    private final long cacheTtlSeconds;

    public BookingController(
            StringRedisTemplate redisTemplate,
            @Value("${app.inventory.endpoint:https://inventory-svc.internal/rooms}") String inventoryEndpoint,
            @Value("${app.report.download-base-url:https://reports.resorts-internal.com/download}") String reportDownloadBaseUrl,
            @Value("${app.cache.ttl-seconds:300}") long cacheTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.inventoryEndpoint = inventoryEndpoint;
        this.reportDownloadBaseUrl = reportDownloadBaseUrl;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        String bookingId = (String) booking.get("bookingId");

        redisTemplate.opsForValue().set("booking:lastBooking:" + bookingId, booking.toString(), Duration.ofSeconds(cacheTtlSeconds));
        redisTemplate.opsForValue().set("booking:guestName:" + bookingId, guestName, Duration.ofSeconds(cacheTtlSeconds));
        redisTemplate.opsForValue().set("booking:cache:" + bookingId, booking.toString(), Duration.ofSeconds(cacheTtlSeconds));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        String lastGuest = redisTemplate.opsForValue().get("booking:guestName:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        String inventoryUrl = inventoryEndpoint + "/available";

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        String reportPath = reportDownloadBaseUrl + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
