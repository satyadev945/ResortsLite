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
    private final String reportContainerName;
    private final long bookingCacheTtlMinutes;

    public BookingController(
            StringRedisTemplate redisTemplate,
            @Value("${app.inventory.endpoint}") String inventoryUrl,
            @Value("${app.report.container-name}") String reportContainerName,
            @Value("${app.cache.booking.ttl-minutes:30}") long bookingCacheTtlMinutes) {
        this.redisTemplate = redisTemplate;
        this.inventoryUrl = inventoryUrl;
        this.reportContainerName = reportContainerName;
        this.bookingCacheTtlMinutes = bookingCacheTtlMinutes;
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        String bookingId = (String) booking.get("bookingId");
        String bearerToken = authorizationHeader == null ? "" : authorizationHeader.replace("Bearer ", "");

        redisTemplate.opsForValue().set("booking:lastBooking:" + bookingId, booking.toString(), Duration.ofMinutes(bookingCacheTtlMinutes));
        redisTemplate.opsForValue().set("booking:guestName:" + bookingId, guestName, Duration.ofMinutes(bookingCacheTtlMinutes));
        redisTemplate.opsForValue().set("booking:auth:" + bookingId,
                String.valueOf(bookingService.authenticateWithAzureAdToken(bearerToken)),
                Duration.ofMinutes(bookingCacheTtlMinutes));

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
        String reportPath = "azureblob://" + reportContainerName + "/" + month + "_bookings.pdf";

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }
}
