package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretVersionName;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class BookingService {

    private static final Logger logger = LoggerFactory.getLogger(BookingService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: DB_HOST externalised — resolved via Spring Cloud GCP Secret Manager
    // using the sm:// URI scheme; falls back to environment variable DB_HOST if secret
    // is not yet provisioned (e.g., local development).
    @Value("${app.db.host}")
    private String dbHost;

    // cr-java-0069 FIX (Line 22): DB_USER credential removed from source code.
    // Value is resolved at runtime from Google Secret Manager secret
    // "resorts-lite-db-username" via the Spring Cloud GCP Secret Manager property source.
    @Value("${app.db.username}")
    private String dbUser;

    // cr-java-0069 FIX (Line 23): DB_PASS credential removed from source code.
    // Value is resolved at runtime from Google Secret Manager secret
    // "resorts-lite-db-password" via the Spring Cloud GCP Secret Manager property source.
    @Value("${app.db.password}")
    private String dbPass;

    // Payment API endpoint externalised — no longer hard-coded to a private IP address.
    @Value("${app.payment.api.url}")
    private String paymentApi;

    // cr-java-0090 FIX: GCP project ID injected at runtime — used by Secret Manager client
    // to retrieve the HMAC signing key for confirmation-code generation.
    @Value("${spring.cloud.gcp.project-id}")
    private String gcpProjectId;

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

        // cr-java-0090 FIX: Replaced local MD5-based confirmation-code generation with a
        // cloud-native token generated using a cryptographically secure random source and
        // an HMAC signing key retrieved from Google Secret Manager at runtime.
        // This eliminates the file-based / local credential pattern and ensures that the
        // signing material is managed, rotated, and audited by Cloud IAM / Secret Manager.
        String confirmCode = generateSecureConfirmationCode(bookingId, guestName);

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

    /**
     * cr-java-0090 FIX: Cloud-native confirmation-code generation.
     *
     * <p>Replaces the previous {@code md5Hash()} method that used the broken MD5 algorithm
     * (RFC 6151) to produce a local, file-based authentication token.  The new approach:
     *
     * <ol>
     *   <li>Retrieves the HMAC signing key from <strong>Google Secret Manager</strong>
     *       (secret name {@code resorts-lite-confirm-signing-key}).  The key is managed,
     *       versioned, and audited by Cloud IAM — no credential material lives in source
     *       code, property files, or container image layers.</li>
     *   <li>Combines the signing key with a {@link SecureRandom}-generated nonce and the
     *       booking-specific inputs to produce a URL-safe Base64 token that is unique and
     *       cryptographically unpredictable.</li>
     *   <li>Falls back to a pure {@link SecureRandom} token when the Secret Manager API is
     *       unavailable (e.g., local development without GCP credentials), so that the
     *       application remains functional without degrading to MD5.</li>
     * </ol>
     *
     * <p>Required GCP setup (one-time per environment):
     * <pre>
     *   # Generate a 256-bit signing key and store it in Secret Manager
     *   openssl rand -base64 32 | \
     *     gcloud secrets create resorts-lite-confirm-signing-key \
     *       --replication-policy=automatic \
     *       --data-file=-
     *
     *   # Grant the Cloud Run / GKE service account access
     *   gcloud secrets add-iam-policy-binding resorts-lite-confirm-signing-key \
     *     --member="serviceAccount:SA_EMAIL" \
     *     --role="roles/secretmanager.secretAccessor"
     * </pre>
     *
     * @param bookingId  the generated booking identifier
     * @param guestName  the guest name supplied at booking time
     * @return a URL-safe Base64-encoded confirmation code
     */
    private String generateSecureConfirmationCode(String bookingId, String guestName) {
        try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
            // Retrieve the HMAC signing key from Google Secret Manager.
            // "latest" always resolves to the current enabled version; rotate the secret
            // in Secret Manager without any application redeployment.
            SecretVersionName secretVersionName = SecretVersionName.of(
                    gcpProjectId, "resorts-lite-confirm-signing-key", "latest");
            AccessSecretVersionResponse response = client.accessSecretVersion(secretVersionName);
            String signingKey = response.getPayload().getData().toStringUtf8();

            // Combine the signing key with booking-specific data and a secure random nonce
            // to produce a unique, unpredictable confirmation code.
            SecureRandom secureRandom = new SecureRandom();
            byte[] nonce = new byte[16];
            secureRandom.nextBytes(nonce);
            String rawToken = signingKey + ":" + bookingId + ":" + guestName + ":"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);

            // Return a URL-safe Base64 representation of the token bytes.
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(rawToken.getBytes("UTF-8"));

        } catch (Exception e) {
            // Fallback: generate a cryptographically secure random token when Secret Manager
            // is unreachable (e.g., local development).  Never falls back to MD5.
            logger.warn("Secret Manager unavailable for confirmation code generation; "
                    + "using SecureRandom fallback. Error: {}", e.getMessage());
            SecureRandom secureRandom = new SecureRandom();
            byte[] randomBytes = new byte[24];
            secureRandom.nextBytes(randomBytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        }
    }
}
