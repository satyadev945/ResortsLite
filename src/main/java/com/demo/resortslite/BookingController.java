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
    private final Duration bookingCacheTtl;
    private final String inventoryUrl;
    private final String reportDownloadBaseUrl;

    public BookingController(
            StringRedisTemplate redisTemplate,
            @Value("${app.booking.cache.ttl-minutes:30}") long bookingCacheTtlMinutes,
            @Value("${app.inventory.endpoint:https://inventory-svc.internal/rooms}") String inventoryUrl,
            @Value("${app.report.download-base-url:https://reports.resorts-internal.com/download}") String reportDownloadBaseUrl) {
        this.redisTemplate = redisTemplate;
        this.bookingCacheTtl = Duration.ofMinutes(bookingCacheTtlMinutes);
        this.inventoryUrl = inventoryUrl;
        this.reportDownloadBaseUrl = reportDownloadBaseUrl;
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        String bookingId = (String) booking.get("bookingId");

        cacheValue("booking:" + bookingId, booking.toString());
        cacheValue("guest:" + bookingId, guestName);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        String lastGuest = redisTemplate.opsForValue().get("guest:" + bookingId);

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
        response.put("inventoryEndpoint", inventoryUrl + "/available");
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

    private void cacheValue(String key, String value) {
        redisTemplate.opsForValue().set(key, value, bookingCacheTtl);
    }
}
