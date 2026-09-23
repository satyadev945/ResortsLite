package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final ReportService reportService;
    private final Path reportBasePath;
    private final String inventoryEndpoint;

    public BookingController(
            BookingService bookingService,
            ReportService reportService,
            @Value("${app.report.base-path:./reports}") String reportBasePath,
            @Value("${app.inventory.endpoint:https://inventory-svc.internal/rooms}") String inventoryEndpoint) {
        this.bookingService = bookingService;
        this.reportService = reportService;
        this.reportBasePath = Paths.get(reportBasePath);
        this.inventoryEndpoint = inventoryEndpoint;
    }

    @PostMapping("/create")
    public Map<String, Object> createBooking(
            @RequestParam String guestName,
            @RequestParam String roomType,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        Map<String, Object> booking = bookingService.createBooking(guestName, roomType, checkIn, checkOut);
        Map<String, Object> response = new HashMap<>();
        response.put("status", "confirmed");
        response.put("booking", booking);
        return response;
    }

    @GetMapping("/status/{bookingId}")
    public Map<String, Object> getBookingStatus(@PathVariable String bookingId) {
        Map<String, Object> result = new HashMap<>();
        result.put("bookingId", bookingId);
        result.put("details", bookingService.getBookingById(bookingId));
        return result;
    }

    @GetMapping("/availability")
    public Map<String, Object> checkAvailability(@RequestParam String roomType) {
        Map<String, Object> response = new HashMap<>();
        response.put("roomType", roomType);
        response.put("inventoryEndpoint", inventoryEndpoint);
        response.put("available", bookingService.isRoomAvailable(roomType));
        return response;
    }

    @GetMapping(value = "/report/download", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> downloadReport(@RequestParam String month) {
        Map<String, Object> generatedReport = reportService.generateMonthlyReport(month, String.valueOf(java.time.Year.now().getValue()));
        Map<String, Object> response = new HashMap<>();
        response.put("reportPath", reportBasePath.resolve("resort_report_" + month + "_" + java.time.Year.now().getValue() + ".csv").toString());
        response.put("message", bookingService.generateReport(month));
        response.put("report", generatedReport);
        response.put("downloadUrl", reportService.buildReportDownloadUrl("resort_report_" + month + "_" + java.time.Year.now().getValue() + ".csv"));
        return response;
    }
}
