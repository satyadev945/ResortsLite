package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AzureCloudConfigService cloudConfigService;
    private final Duration bookingCacheTtl;

    public BookingController(BookingService bookingService,
                             RedisTemplate<String, Object> redisTemplate,
                             AzureCloudConfigService cloudConfigService,
                             @Value("${app.cache.booking.ttl:PT30M}") Duration bookingCacheTtl) {
        this.bookingService = bookingService;
        this.redisTemplate = redisTemplate;
        this.cloudConfigService = cloudConfigService;
        this.bookingCacheTtl = bookingCacheTtl;
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            HttpSession session) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        String bookingId = (String) booking.get("bookingId");

        redisTemplate.opsForValue().set(redisKey("booking", bookingId), booking, bookingCacheTtl);
        redisTemplate.opsForValue().set(redisKey("session", session.getId(), "lastBooking"), bookingId, bookingCacheTtl);
        redisTemplate.opsForValue().set(redisKey("session", session.getId(), "guestName"), guestName, bookingCacheTtl);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(
            @PathVariable String bookingId,
            HttpSession session) {

        Object lastGuest = redisTemplate.opsForValue().get(redisKey("session", session.getId(), "guestName"));
        Object cachedBooking = redisTemplate.opsForValue().get(redisKey("booking", bookingId));

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        String inventoryUrl = cloudConfigService.getConfiguration(
                "app.inventory.endpoint",
                "app.inventory.endpoint",
                "");

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        String reportBlobName = month + "_bookings.pdf";
        Map<String, Object> response = new HashMap<>();
        response.put("reportBlobName", reportBlobName);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    private String redisKey(String... parts) {
        StringBuilder key = new StringBuilder("resortslite");
        for (String part : parts) {
            key.append(':').append(part);
        }
        return key.toString();
    }
}
