package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private ReportService reportService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final String inventoryUrl;
    private final String reportDownloadBaseUrl;
    private final long bookingCacheTtlSeconds;

    public BookingController(
            @Value("${app.config.inventory-url}") String inventoryUrl,
            @Value("${app.config.report-download-url}") String reportDownloadBaseUrl,
            @Value("${app.cache.booking.ttl-seconds}") long bookingCacheTtlSeconds) {
        this.inventoryUrl = inventoryUrl;
        this.reportDownloadBaseUrl = reportDownloadBaseUrl;
        this.bookingCacheTtlSeconds = bookingCacheTtlSeconds;
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        String bookingId = (String) booking.get("bookingId");

        redisTemplate.opsForValue().set(buildSessionKey("lastBooking", bookingId), booking.toString(), Duration.ofSeconds(bookingCacheTtlSeconds));
        redisTemplate.opsForValue().set(buildSessionKey("guestName", bookingId), guestName, Duration.ofSeconds(bookingCacheTtlSeconds));
        redisTemplate.opsForValue().set(buildCacheKey(bookingId), booking.toString(), Duration.ofSeconds(bookingCacheTtlSeconds));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        String lastGuest = redisTemplate.opsForValue().get(buildSessionKey("guestName", bookingId));

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
        String reportPath = reportDownloadBaseUrl + "/" + month + "_bookings.pdf";
        reportService.scheduleReportGeneration(month);

        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportPath);
        response.put("message", bookingService.generateReport(month));
        return response;
    }

    private String buildSessionKey(String keyType, String bookingId) {
        return "booking:session:" + keyType + ":" + bookingId;
    }

    private String buildCacheKey(String bookingId) {
        return "booking:cache:" + bookingId;
    }
}
