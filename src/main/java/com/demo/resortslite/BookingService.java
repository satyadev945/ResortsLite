package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cz-java-0082: Autowired Azure Service Bus publisher for async decoupled messaging.
    // Replaces tightly-coupled synchronous reference to PAYMENT_API in generateReport()
    // with an async event published to Azure Service Bus report queue on AKS.
    @Autowired
    private AzureServiceBusPublisher serviceBusPublisher;

    // VIOLATION [Security Health / Critical]: Hardcoded database credentials in source code.
    // If this repo is pushed to GitHub (even private), credentials are permanently exposed
    // in git history. AWS Secrets Manager or Parameter Store must be used instead.
    // cz-java-0062: Replaced hardcoded IP/hostname "db-prod.resorts-internal.com" with
    // environment variable DB_HOST. Set DB_HOST in AKS pod spec or Azure Key Vault CSI
    // Driver — never hardcode network addresses or IPs in source code.
    @Value("${DB_HOST:localhost}")
    private String dbHost;
    private static final String DB_USER = "admin";                         // sec-cred-001
    private static final String DB_PASS = "Resort$Pass#2019!";             // sec-cred-001

    // cz-java-0082: Removed hardcoded PAYMENT_API static field. The tightly-coupled
    // synchronous call to the payment service endpoint has been replaced with an async
    // Azure Service Bus event (ReportGenerationRequested) published to the report queue.
    // The downstream payment/reporting microservice on AKS consumes this event independently,
    // enabling autonomous scaling and fault isolation without direct HTTP coupling.
    // Payment endpoint is no longer referenced directly — configure via PAYMENT_API_URL
    // env var in the downstream payment microservice's AKS pod spec if needed.

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // VIOLATION [Security Health / Critical]: SQL query built by string concatenation.
        // An attacker can pass guestName = "'; DROP TABLE bookings; --" to destroy data.
        // Use parameterised queries (JdbcTemplate with '?') to prevent SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('" // sql-inject-001
                + bookingId + "', '" + guestName + "', '" + roomType               // sql-inject-001
                + "', '" + checkIn + "', '" + checkOut + "')";                     // sql-inject-001
        jdbcTemplate.execute(sql);

        // VIOLATION [Security Health / High]: MD5 is a broken hash algorithm (RFC 6151).
        // Do not use MD5 for any security-related hashing. Use SHA-256 or bcrypt.
        String confirmCode = md5Hash(bookingId + guestName); // sec-weak-hash-001

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // cz-java-0062: Using injected dbHost (from env var DB_HOST) instead of hardcoded value.
        booking.put("dbHost", dbHost);
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // VIOLATION [Security Health / Critical]: SQL injection via string concatenation.
        // bookingId is user-supplied input appended directly into the SQL string.
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'"; // sql-inject-001
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // VIOLATION [Code Sustainability / High]: High cyclomatic complexity.
    // This method has 9+ decision branches. Automated transformation tools flag methods
    // above complexity threshold as high maintenance risk and transformation blockers.
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = 0;
        if (roomType.equals("STANDARD")) { basePrice = 120.0; }
        else if (roomType.equals("DELUXE")) { basePrice = 200.0; }
        else if (roomType.equals("SUITE")) { basePrice = 350.0; }
        else if (roomType.equals("VILLA")) { basePrice = 600.0; }
        else { basePrice = 120.0; }
        if (season.equals("PEAK")) { basePrice = basePrice * 1.5; }
        else if (season.equals("OFF")) { basePrice = basePrice * 0.8; }
        if (loyalty.equals("GOLD")) { basePrice = basePrice * 0.9; }
        else if (loyalty.equals("PLATINUM")) { basePrice = basePrice * 0.8; }
        else if (loyalty.equals("DIAMOND")) { basePrice = basePrice * 0.7; }
        if (nights >= 7) { basePrice = basePrice * 0.95; }
        else if (nights >= 14) { basePrice = basePrice * 0.90; }
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    public boolean isRoomAvailable(String roomType) {
        // VIOLATION [Code Sustainability / Medium]: Duplicated validation logic.
        // Same room type validation is repeated here and in calculateRoomPrice.
        // Should be extracted to a shared RoomType enum or validator.
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE") // dup-logic-001
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) { // dup-logic-001
            return false;
        }
        return true;
    }

    /**
     * cz-java-0082: Replaced tightly-coupled synchronous reference to PAYMENT_API with
     * async Azure Service Bus event publishing. Instead of directly calling the payment
     * service endpoint (PAYMENT_API = "http://10.0.1.45:9090/payments/charge"), a
     * 'ReportGenerationRequested' event is published to the Azure Service Bus report queue.
     * The downstream report/payment processor microservice on AKS consumes this event
     * independently, decoupling the booking domain from the payment/reporting domain and
     * enabling autonomous scaling and fault isolation on AKS.
     */
    public String generateReport(String month) {
        // cz-java-0082: Build the report generation event payload for async processing.
        String correlationId = UUID.randomUUID().toString();
        Map<String, Object> reportPayload = new HashMap<>();
        reportPayload.put("correlationId", correlationId);
        reportPayload.put("month", month);
        reportPayload.put("reportType", "BOOKING_REPORT");
        reportPayload.put("requestedAt", new java.util.Date().toString());

        // cz-java-0082: Publish async event to Azure Service Bus report queue.
        // Replaces the synchronous PAYMENT_API reference that tightly coupled this
        // service to the payment infrastructure endpoint.
        serviceBusPublisher.publishReportGenerationEvent(reportPayload, correlationId);

        return "Report generation event published to Azure Service Bus for month: " + month
                + ". CorrelationId: " + correlationId
                + ". The downstream report processor microservice on AKS will handle processing asynchronously.";
    }

    private String md5Hash(String input) { // sec-weak-hash-001
        try {
            MessageDigest md = MessageDigest.getInstance("MD5"); // sec-weak-hash-001
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
