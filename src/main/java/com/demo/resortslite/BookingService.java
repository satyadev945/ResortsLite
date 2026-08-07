package com.demo.resortslite;

import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String keyVaultUrl;
    private final String dbUserSecretName;
    private final String dbPassSecretName;
    private final String appConfigConnectionString;
    private final String appConfigEndpoint;
    private final String paymentApiFallback;

    public BookingService(
            @Value("${app.keyvault.url:}") String keyVaultUrl,
            @Value("${app.keyvault.db-user-secret:db-user}") String dbUserSecretName,
            @Value("${app.keyvault.db-pass-secret:db-password}") String dbPassSecretName,
            @Value("${app.config.connection-string:}") String appConfigConnectionString,
            @Value("${app.config.endpoint:}") String appConfigEndpoint,
            @Value("${app.payment.endpoint:https://payment-svc.internal/charge}") String paymentApiFallback) {
        this.keyVaultUrl = keyVaultUrl;
        this.dbUserSecretName = dbUserSecretName;
        this.dbPassSecretName = dbPassSecretName;
        this.appConfigConnectionString = appConfigConnectionString;
        this.appConfigEndpoint = appConfigEndpoint;
        this.paymentApiFallback = paymentApiFallback;
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                             String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbUserSecret", getSecretValue(dbUserSecretName));
        booking.put("dbPasswordSecret", getSecretValue(dbPassSecretName));
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        String sql = "SELECT * FROM bookings WHERE id = ?";
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql, bookingId);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

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
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE")
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) {
            return false;
        }
        return true;
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + resolvePaymentApi();
    }

    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }

    private String getSecretValue(String secretName) {
        if (keyVaultUrl == null || keyVaultUrl.trim().isEmpty()) {
            return secretName;
        }
        try {
            SecretClient secretClient = new SecretClientBuilder()
                    .vaultUrl(keyVaultUrl)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
            return secretClient.getSecret(secretName).getValue();
        } catch (Exception e) {
            return secretName;
        }
    }

    private String resolvePaymentApi() {
        try {
            if (appConfigConnectionString != null && !appConfigConnectionString.trim().isEmpty()) {
                ConfigurationClient client = new ConfigurationClientBuilder()
                        .connectionString(appConfigConnectionString)
                        .buildClient();
                return client.getConfigurationSetting("app.payment.endpoint", null).getValue();
            }
            if (appConfigEndpoint != null && !appConfigEndpoint.trim().isEmpty()) {
                ConfigurationClient client = new ConfigurationClientBuilder()
                        .endpoint(appConfigEndpoint)
                        .credential(new DefaultAzureCredentialBuilder().build())
                        .buildClient();
                return client.getConfigurationSetting("app.payment.endpoint", null).getValue();
            }
        } catch (Exception ignored) {
        }
        return paymentApiFallback;
    }
}
