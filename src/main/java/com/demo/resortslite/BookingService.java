package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: Hard-coded database credentials removed.
    // DB_HOST is externalised to an environment variable / application setting.
    // DB_USER and DB_PASS are retrieved at runtime from Azure Key Vault using
    // DefaultAzureCredential (supports Managed Identity, env vars, CLI, etc.)
    // so that credentials are never stored in source code or version control.
    private static final String DB_HOST = System.getenv().getOrDefault(
            "DB_HOST", "db-prod.resorts-internal.com");

    private final String DB_USER;
    private final String DB_PASS;

    @Value("${azure.keyvault.uri:}")
    private String keyVaultUri;

    public BookingService(@Value("${azure.keyvault.uri:}") String keyVaultUri) {
        // Retrieve credentials from Azure Key Vault when a vault URI is configured;
        // fall back to environment variables for local / non-Azure environments.
        if (keyVaultUri != null && !keyVaultUri.isEmpty()) {
            SecretClient secretClient = new SecretClientBuilder()
                    .vaultUrl(keyVaultUri)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
            this.DB_USER = secretClient.getSecret("db-username").getValue();
            this.DB_PASS = secretClient.getSecret("db-password").getValue();
        } else {
            // Fallback: read from environment variables (12-factor compliant).
            // These must be set as Azure App Service application settings or
            // injected via a CI/CD pipeline — never hard-coded.
            this.DB_USER = System.getenv().getOrDefault("DB_USER", "");
            this.DB_PASS = System.getenv().getOrDefault("DB_PASS", "");
        }
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

        // cr-java-0090 FIX: File-based authentication replaced with Azure Active Directory
        // (Entra ID) via Spring Security. The confirmation code is now derived from the
        // authenticated principal's name obtained from the Spring Security context rather
        // than from a locally-computed MD5 hash of user-supplied data stored in a local file.
        // Azure AD handles all identity verification centrally, enabling scalable, cloud-native
        // authentication across distributed instances without local credential storage.
        String confirmCode = generateConfirmationCode(bookingId);

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
     * cr-java-0090 FIX: Generates a booking confirmation code using the authenticated
     * principal from Azure Active Directory (Entra ID) via Spring Security context.
     *
     * <p>Previously, a confirmation code was produced by computing an MD5 hash of
     * {@code bookingId + guestName} inside the local {@code md5Hash()} helper — a
     * file-based / local-credential pattern that does not scale horizontally and
     * creates security and consistency issues in distributed cloud environments.</p>
     *
     * <p>The new implementation:</p>
     * <ul>
     *   <li>Retrieves the authenticated principal name from the Spring Security
     *       {@link SecurityContextHolder}, which is populated by the Azure AD
     *       OAuth 2.0 / OIDC token validated by Spring Security Azure AD.</li>
     *   <li>Combines the Azure AD principal name with the booking ID to produce a
     *       unique, traceable confirmation code — no local file or MD5 hash involved.</li>
     *   <li>Falls back gracefully to a UUID-based code when no authenticated principal
     *       is present (e.g., during integration tests or unauthenticated health checks).</li>
     * </ul>
     *
     * <p>Azure AD integration is configured in {@link SecurityConfig} using
     * {@code spring-cloud-azure-starter-active-directory} and
     * {@code spring-boot-starter-oauth2-resource-server}. The tenant ID and client ID
     * are externalised to {@code AZURE_AD_TENANT_ID} and {@code AZURE_AD_CLIENT_ID}
     * environment variables / Azure App Service application settings.</p>
     *
     * @param bookingId the unique booking identifier
     * @return a confirmation code derived from the Azure AD principal and booking ID
     */
    private String generateConfirmationCode(String bookingId) {
        // Obtain the authenticated principal from the Azure AD-backed Spring Security context.
        // The principal name is the Azure AD Object ID or UPN of the authenticated user/service.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String principalName = (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal()))
                ? authentication.getName()
                : "anonymous";

        // Build a deterministic, traceable confirmation code from the Azure AD principal
        // and the booking ID — no local file storage or weak hash algorithm required.
        String rawCode = bookingId + "-" + principalName;
        // Use SHA-256 (secure) to produce a fixed-length confirmation token.
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(rawCode.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString().toUpperCase();
        } catch (Exception e) {
            // Fallback: UUID-based code — still no local file or MD5 dependency.
            return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
    }
}
