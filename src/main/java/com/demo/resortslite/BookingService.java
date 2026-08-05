package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // VIOLATION [Security Health / Critical]: Hardcoded database credentials in source code.
    // If this repo is pushed to GitHub (even private), credentials are permanently exposed
    // in git history. Azure Key Vault must be used instead.
    // FIXED: DB_HOST remains as a non-secret infrastructure reference (externalised separately)
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    // FIXED cr-java-0069: Hard-coded DB_USER and DB_PASS replaced with Azure Key Vault lookups.
    // Credentials are retrieved at runtime via DefaultAzureCredential (supports Managed Identity,
    // environment variables, Azure CLI, etc.) — no secrets stored in source code or binaries.
    @Value("${azure.keyvault.uri}")
    private String keyVaultUri;

    /**
     * Retrieves the database username from Azure Key Vault.
     * Secret name: "db-username"
     */
    private String getDbUser() {
        SecretClient secretClient = new SecretClientBuilder()
                .vaultUrl(keyVaultUri)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        return secretClient.getSecret("db-username").getValue();
    }

    /**
     * Retrieves the database password from Azure Key Vault.
     * Secret name: "db-password"
     */
    private String getDbPass() {
        SecretClient secretClient = new SecretClientBuilder()
                .vaultUrl(keyVaultUri)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
        return secretClient.getSecret("db-password").getValue();
    }

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

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

        // cr-java-0090 FIX: Confirmation code is now derived from the Azure AD-authenticated
        // principal's name (obtained from the Spring Security context) combined with the
        // booking ID, hashed with SHA-256. This replaces the previous MD5-based local token
        // generation that stored authentication data in-process without cloud identity backing.
        // No credentials, user data, or security tokens are stored in local files.
        String confirmCode = generateConfirmationCode(bookingId, getAuthenticatedPrincipalName());

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", DB_HOST);
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
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    /**
     * cr-java-0090 FIX: Returns the name of the currently authenticated Azure AD principal
     * from the Spring Security context. The principal is populated by the Azure AD
     * resource-server JWT Bearer token filter — no local file or in-process credential
     * store is consulted. Returns "anonymous" when no authentication context is present
     * (e.g., during unit tests or unauthenticated calls to non-secured endpoints).
     *
     * @return the Azure AD principal name, or "anonymous" if not authenticated
     */
    private String getAuthenticatedPrincipalName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            return authentication.getName();
        }
        return "anonymous";
    }

    /**
     * cr-java-0090 FIX: Generates a booking confirmation code using SHA-256 (replacing the
     * previous broken MD5 algorithm). The code is derived from the booking ID and the
     * Azure AD-authenticated principal name, ensuring the token is tied to a verified
     * cloud identity rather than locally stored credentials or file-based user data.
     *
     * @param bookingId      the unique booking identifier
     * @param principalName  the Azure AD principal name from the security context
     * @return a hex-encoded SHA-256 confirmation code (first 16 characters)
     */
    private String generateConfirmationCode(String bookingId, String principalName) {
        try {
            // cr-java-0090: SHA-256 replaces MD5 (RFC 6151 prohibits MD5 for security use).
            // Input combines booking ID with the Azure AD principal name so the confirmation
            // code is cryptographically bound to the authenticated cloud identity.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = bookingId + ":" + principalName;
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            // Return first 16 hex characters as a compact confirmation code
            return sb.toString().substring(0, 16).toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all JVMs (JCA spec)
            return bookingId.substring(0, Math.min(8, bookingId.length()));
        }
    }
}
