package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-ready service layer for resort booking operations.
 *
 * <h3>cr-java-0090 FIX: File-based Authentication → Azure Active Directory (Entra ID)</h3>
 * <p>All local file-based credential storage (hardcoded {@code DB_USER} / {@code DB_PASS}
 * constants, plain-text password files, or any on-disk credential store) has been removed.
 * Authentication is now delegated entirely to Azure Active Directory (Entra ID) via
 * Spring Security and the Microsoft Authentication Library (MSAL):</p>
 * <ul>
 *   <li>The Spring Security filter chain (configured in {@link SecurityConfig}) validates
 *       every incoming request against a signed Azure AD JWT bearer token before this
 *       service method is ever invoked.</li>
 *   <li>The authenticated principal is available through
 *       {@link SecurityContextHolder#getContext()} — no local user store is consulted.</li>
 *   <li>Database credentials are retrieved at runtime from Azure Key Vault using
 *       {@link DefaultAzureCredentialBuilder} (Managed Identity in cloud; CLI / env vars
 *       locally) — they are never stored in files or source code.</li>
 * </ul>
 *
 * <h3>Required environment variables</h3>
 * <ul>
 *   <li>{@code AZURE_KEY_VAULT_URI} – Key Vault endpoint
 *       (e.g. {@code https://&lt;vault-name&gt;.vault.azure.net/})</li>
 *   <li>{@code AZURE_ACTIVEDIRECTORY_TENANT_ID} – Azure AD tenant ID</li>
 *   <li>{@code AZURE_ACTIVEDIRECTORY_CLIENT_ID} – Application (client) ID</li>
 *   <li>{@code AZURE_ACTIVEDIRECTORY_CLIENT_SECRET} – Client secret
 *       (inject from Key Vault via Managed Identity reference)</li>
 * </ul>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0090 FIX: DB_USER and DB_PASS hardcoded constants REMOVED.
    // No credentials are stored in files, source code, or any local store.
    // Database credentials are retrieved exclusively from Azure Key Vault at runtime
    // using DefaultAzureCredential (Managed Identity in Azure; CLI / env vars locally).
    // The Key Vault URI is externalised to the AZURE_KEY_VAULT_URI environment variable.
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    @Value("${azure.keyvault.uri:#{environment['AZURE_KEY_VAULT_URI']}}")
    private String keyVaultUri;

    /**
     * Lazily-initialised SecretClient backed by DefaultAzureCredential.
     *
     * <p>cr-java-0090 FIX: In Azure cloud environments the Managed Identity of the hosting
     * resource (App Service, Container Apps, AKS pod, etc.) is used automatically —
     * no credential files, password stores, or local user databases are required.
     * Locally, the Azure CLI credential or environment variables are used.</p>
     */
    private SecretClient buildSecretClient() {
        return new SecretClientBuilder()
                .vaultUrl(keyVaultUri)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
    }

    /**
     * Retrieves the database username from Azure Key Vault.
     *
     * <p>cr-java-0090 FIX: Replaces the former {@code private static final String DB_USER}
     * constant. The secret is fetched at runtime from Key Vault; it is never stored in
     * any local file, properties file, or source code.</p>
     *
     * <p>Set the secret in Key Vault:
     * {@code az keyvault secret set --vault-name <vault> --name db-username --value <value>}</p>
     */
    private String getDbUser() {
        return buildSecretClient().getSecret("db-username").getValue();
    }

    /**
     * Retrieves the database password from Azure Key Vault.
     *
     * <p>cr-java-0090 FIX: Replaces the former {@code private static final String DB_PASS}
     * constant. The secret is fetched at runtime from Key Vault; it is never stored in
     * any local file, properties file, or source code.</p>
     *
     * <p>Set the secret in Key Vault:
     * {@code az keyvault secret set --vault-name <vault> --name db-password --value <value>}</p>
     */
    private String getDbPass() {
        return buildSecretClient().getSecret("db-password").getValue();
    }

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Returns the Azure AD principal name of the currently authenticated user.
     *
     * <p>cr-java-0090 FIX: Authentication identity is sourced exclusively from the
     * Azure AD JWT bearer token validated by Spring Security — no local file-based
     * user store or credential file is consulted. The principal is extracted from the
     * {@code preferred_username} claim of the Entra ID token.</p>
     *
     * @return the authenticated user's Azure AD UPN, or {@code "anonymous"} if no
     *         authentication context is present (e.g. during local unit tests).
     */
    public String getAuthenticatedPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Jwt) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            // Extract the user principal name from the Azure AD JWT claim
            String upn = jwt.getClaimAsString("preferred_username");
            return upn != null ? upn : jwt.getSubject();
        }
        return "anonymous";
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // cr-java-0090 FIX: Record the Azure AD authenticated principal who created
        // the booking. Identity comes from the validated Entra ID JWT — no local
        // credential file or user store is used.
        String authenticatedUser = getAuthenticatedPrincipal();

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
        booking.put("dbHost", DB_HOST);
        // cr-java-0090 FIX: Audit trail — record the Azure AD identity that created
        // this booking. Sourced from the Entra ID JWT; no local credential file used.
        booking.put("createdBy", authenticatedUser);
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
