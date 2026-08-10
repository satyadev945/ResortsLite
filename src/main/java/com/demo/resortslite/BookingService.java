package com.demo.resortslite;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AzureConfigurationService configurationService;

    private final SecretClient secretClient;

    public BookingService(@Value("${azure.key-vault.endpoint:}") String keyVaultEndpoint) {
        if (StringUtils.hasText(keyVaultEndpoint)) {
            TokenCredential credential = new DefaultAzureCredentialBuilder().build();
            this.secretClient = new SecretClientBuilder()
                    .vaultUrl(keyVaultEndpoint)
                    .credential(credential)
                    .buildClient();
        } else {
            this.secretClient = null;
        }
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        jdbcTemplate.update(
                "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)",
                bookingId, guestName, roomType, checkIn, checkOut);

        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", resolveSecretOrConfiguration("db-host", "spring.datasource.url", "externalized"));
        booking.put("credentialSource", secretClient == null ? "Externalized environment/App Configuration" : "Azure Key Vault");
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap("SELECT * FROM bookings WHERE id = ?", bookingId);
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
        String paymentEndpoint = configurationService.getString(
                "app:payment:endpoint",
                "app.payment.endpoint",
                "https://payment.example.invalid/charge");
        return "Report generation triggered for: " + month + " via " + paymentEndpoint;
    }

    public String resolveDatabaseUser() {
        return resolveSecretOrConfiguration("db-username", "spring.datasource.username", "");
    }

    public String resolveDatabasePassword() {
        return resolveSecretOrConfiguration("db-password", "spring.datasource.password", "");
    }

    private String resolveSecretOrConfiguration(String secretName, String propertyName, String defaultValue) {
        if (secretClient != null) {
            try {
                return secretClient.getSecret(secretName).getValue();
            } catch (RuntimeException ignored) {
                // Fall back to Azure App Configuration/environment properties below.
            }
        }
        return configurationService.getString("secret:" + secretName, propertyName, defaultValue);
    }

    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
