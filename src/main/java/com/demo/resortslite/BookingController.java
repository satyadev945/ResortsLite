package com.demo.resortslite;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private static final Duration BOOKING_CACHE_TTL = Duration.ofMinutes(30);

    private final BookingService bookingService;
    private final AzureConfigurationService configurationService;
    private final RedisTemplate<String, Object> redisTemplate;

    public BookingController(BookingService bookingService,
                             AzureConfigurationService configurationService,
                             RedisTemplate<String, Object> redisTemplate) {
        this.bookingService = bookingService;
        this.configurationService = configurationService;
        this.redisTemplate = redisTemplate;
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        String bookingId = (String) booking.get("bookingId");

        redisTemplate.opsForValue().set(cacheKey(bookingId), booking, BOOKING_CACHE_TTL);
        redisTemplate.opsForValue().set(lastGuestKey(bookingId), guestName, BOOKING_CACHE_TTL);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        Object cachedGuest = redisTemplate.opsForValue().get(lastGuestKey(bookingId));
        Object cachedBooking = redisTemplate.opsForValue().get(cacheKey(bookingId));

        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("cachedGuest", cachedGuest);
        result.put("details", cachedBooking != null ? cachedBooking : bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        String inventoryUrl = configurationService.getSetting(
                "inventory:availability-url",
                "INVENTORY_AVAILABILITY_URL",
                "https://inventory-service.resorts.example.com/rooms/available");

        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryUrl);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping("/report/download")
    public Map<String, Object> downloadReport(@RequestParam String month) {
        Map<String, Object> response = new HashMap<>();
        response.put("reportLocation", "Azure Blob Storage");
        response.put("reportName", month + "_bookings.pdf");
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    private String cacheKey(String bookingId) {
        return "booking:" + bookingId;
    }

    private String lastGuestKey(String bookingId) {
        return "booking:lastGuest:" + bookingId;
    }
}
