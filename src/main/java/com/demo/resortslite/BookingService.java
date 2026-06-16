package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${aws.secrets.db.secret.name}")
    private String dbSecretName;

    @Value("${aws.secrets.auth.secret.name}")
    private String authSecretName;

    @Value("${aws.region}")
    private String awsRegion;

    @Value("${app.payment.endpoint}")
    private String paymentApiEndpoint;

    // Cloud-native: Database credentials loaded from AWS Secrets Manager
    private String dbHost;
    private String dbUser;
    private String dbPassword;

    // Cloud-native: Authentication credentials loaded from AWS Secrets Manager
    private Map<String, String> authCredentials;

    @PostConstruct
    public void init() {
        // Load database credentials from AWS Secrets Manager
        loadDatabaseCredentials();
        // Load authentication credentials from AWS Secrets Manager
        loadAuthenticationCredentials();
    }

    /**
     * Loads database credentials from AWS Secrets Manager.
     * Replaces hard-coded credentials (blocker-8, blocker-9: cr-java-0069)
     */
    private void loadDatabaseCredentials() {
        try {
            SecretsManagerClient secretsClient = SecretsManagerClient.builder()
                    .region(software.amazon.awssdk.regions.Region.of(awsRegion))
                    .build();

            GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse getSecretValueResponse = secretsClient.getSecretValue(getSecretValueRequest);
            String secret = getSecretValueResponse.secretString();

            // Parse JSON secret
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode secretJson = objectMapper.readTree(secret);

            this.dbHost = secretJson.get("host").asText();
            this.dbUser = secretJson.get("username").asText();
            this.dbPassword = secretJson.get("password").asText();

            secretsClient.close();
        } catch (Exception e) {
            // Fallback to environment variables if Secrets Manager is not available
            this.dbHost = System.getenv().getOrDefault("DB_HOST", "localhost");
            this.dbUser = System.getenv().getOrDefault("DB_USERNAME", "sa");
            this.dbPassword = System.getenv().getOrDefault("DB_PASSWORD", "");
            System.err.println("Warning: Could not load DB credentials from Secrets Manager, using environment variables: " + e.getMessage());
        }
    }

    /**
     * Loads authentication credentials from AWS Secrets Manager.
     * Replaces file-based authentication (blocker-18: cr-java-0090)
     */
    private void loadAuthenticationCredentials() {
        try {
            SecretsManagerClient secretsClient = SecretsManagerClient.builder()
                    .region(software.amazon.awssdk.regions.Region.of(awsRegion))
                    .build();

            GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
                    .secretId(authSecretName)
                    .build();

            GetSecretValueResponse getSecretValueResponse = secretsClient.getSecretValue(getSecretValueRequest);
            String secret = getSecretValueResponse.secretString();

            // Parse JSON secret
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode secretJson = objectMapper.readTree(secret);

            this.authCredentials = new HashMap<>();
            secretJson.fields().forEachRemaining(entry -> 
                authCredentials.put(entry.getKey(), entry.getValue().asText())
            );

            secretsClient.close();
        } catch (Exception e) {
            // Fallback to empty credentials map
            this.authCredentials = new HashMap<>();
            System.err.println("Warning: Could not load auth credentials from Secrets Manager: " + e.getMessage());
        }
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Fixed: Use parameterized query to prevent SQL injection
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, bookingId, guestName, roomType, checkIn, checkOut);

        // Fixed: Use SHA-256 instead of MD5 for secure hashing
        String confirmCode = sha256Hash(bookingId + guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", dbHost);
        return booking;
    }

    public Map<String, Object> getBookingById(String bookingId) {
        // Fixed: Use parameterized query to prevent SQL injection
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
        return "Report generation triggered for: " + month + " via " + paymentApiEndpoint;
    }

    /**
     * Authenticates user using credentials from AWS Secrets Manager.
     * Replaces file-based authentication (blocker-18: cr-java-0090)
     */
    public boolean authenticateUser(String username, String password) {
        if (authCredentials == null || authCredentials.isEmpty()) {
            return false;
        }
        String storedPassword = authCredentials.get(username);
        return storedPassword != null && storedPassword.equals(password);
    }

    /**
     * SHA-256 hash function - replaces insecure MD5
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { 
                sb.append(String.format("%02x", b)); 
            }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
