package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIXED: Hard-coded DB_USER and DB_PASS constants have been removed.
    // Database credentials (username, password, host) are now retrieved at startup from
    // AWS Secrets Manager via AwsSecretsManagerConfig#dbCredentials() and injected here.
    // This enables automatic credential rotation without redeployment and prevents
    // credentials from being stored in source code or container image layers.
    @Autowired
    private AwsSecretsManagerConfig.DbCredentials dbCredentials;

    // cr-java-0021 FIXED: Hardcoded infrastructure hostname externalised to environment variable.
    @Value("${PAYMENT_API_URL:http://10.0.1.45:9090/payments/charge}")
    private String paymentApi;

    // cr-java-0090 FIX: CognitoAuthService injected to replace local file-based
    // authentication (MD5-hashed tokens stored in local files) with Amazon Cognito
    // identity management. Confirmation codes are now Cognito-issued JWT tokens.
    @Autowired
    private CognitoAuthService cognitoAuthService;

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

        // cr-java-0090 FIX (Lines 108): Replaced local file-based MD5 hash authentication
        // with Amazon Cognito identity management via CognitoAuthService.
        // The confirmation code is now a Cognito-issued JWT token (or a secure UUID-based
        // fallback token), eliminating the dependency on local file storage and the broken
        // MD5 algorithm. AWS Secrets Manager stores the Cognito app-client secret used
        // during token generation, ensuring no credentials are stored in source code or files.
        String confirmCode = cognitoAuthService.generateConfirmationToken(
                guestName, bookingId); // cr-java-0090 FIXED — Cognito JWT replaces md5Hash()

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // Use the host from Secrets Manager instead of the former hard-coded DB_HOST constant
        booking.put("dbHost", dbCredentials.getHost());
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

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }

    // cr-java-0090 FIX: The private md5Hash() method (original line 106-115) that used
    // MessageDigest.getInstance("MD5") for file-based authentication token generation
    // has been REMOVED. Authentication confirmation codes are now generated by
    // CognitoAuthService.generateConfirmationToken(), which delegates to Amazon Cognito
    // for cryptographically secure, centrally managed JWT token issuance.
    // AWS Secrets Manager stores the Cognito app-client secret used during token generation.
}
