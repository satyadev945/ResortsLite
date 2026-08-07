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
    private final String inventoryUrl;
    private final String reportContainerBaseUrl;
    private final Duration bookingCacheTtl;

    public BookingController(
            StringRedisTemplate redisTemplate,
            @Value("${app.inventory.endpoint}") String inventoryUrl,
            @Value("${app.storage.blob.endpoint}/${app.storage.blob.container-name}") String reportContainerBaseUrl,
            @Value("${app.cache.booking.ttl-seconds}") long bookingCacheTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.inventoryUrl = inventoryUrl;
        this.reportContainerBaseUrl = reportContainerBaseUrl;
        this.bookingCacheTtl = Duration.ofSeconds(bookingCacheTtlSeconds);
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);

        String bookingId = (String) booking.get("bookingId");
        redisTemplate.opsForValue().set("booking:lastBooking", booking.toString(), bookingCacheTtl);
        redisTemplate.opsForValue().set("booking:guestName:" + bookingId, guestName, bookingCacheTtl);
        redisTemplate.opsForValue().set("booking:cache:" + bookingId, booking.toString(), bookingCacheTtl);

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
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        String reportPath = reportContainerBaseUrl + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        response.put("auth", bookingService.getAuthenticationConfiguration());
        return response;
    }
}
