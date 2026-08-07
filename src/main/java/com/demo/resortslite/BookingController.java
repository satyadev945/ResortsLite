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

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${app.inventory.endpoint}")
    private String inventoryUrl;

    @Value("${app.redis.ttl-seconds:900}")
    private long redisTtlSeconds;

    @Value("${app.report.container-name:reports}")
    private String reportContainerName;

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        String bookingId = (String) booking.get("bookingId");

        cacheValue("booking:lastBooking", bookingId);
        cacheValue("booking:guest:" + bookingId, guestName);
        cacheValue("booking:cache:" + bookingId, booking.toString());

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        response.put("stateStore", "Azure Cache for Redis");
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        String lastGuest = redisTemplate.opsForValue().get("booking:guest:" + bookingId);

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("sessionGuest", lastGuest);
        result.put("details", bookingService.getBookingById(bookingId));
        result.put("stateStore", "Azure Cache for Redis");
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        response.put("cacheProvider", "Azure Cache for Redis");
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        String reportPath = "azureblob://" + reportContainerName + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    private void cacheValue(String key, String value) {
        redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(redisTtlSeconds));
    }
}
