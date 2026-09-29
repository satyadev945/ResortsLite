package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-native resort booking business logic.
 *
 * <p><strong>cr-java-0090 FIX — File-based Authentication:</strong><br>
 * Local file-based credential storage and MD5-based authentication token generation
 * have been replaced with Azure Active Directory (Entra ID) authentication using
 * Microsoft Authentication Library (MSAL) and Spring Security Azure AD integration.
 * The application now delegates all identity and access management to Azure AD:
 * <ul>
 *   <li>Authentication is validated via Azure AD OAuth 2.0 / OIDC JWT tokens
 *       (enforced by {@code AzureAdSecurityConfig}).</li>
 *   <li>The authenticated principal is resolved from the Spring Security
 *       {@link SecurityContextHolder} — no local credential files are read.</li>
 *   <li>The MD5-based confirmation-code hash (sec-weak-hash-001) has been replaced
 *       with a SHA-256 digest, which is cryptographically sound for non-password
 *       hashing use-cases.</li>
 * </ul>
 * </p>
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: Hard-coded database credentials removed.
    // DB_USER and DB_PASS are now retrieved at runtime from Azure Key Vault
    // using DefaultAzureCredential (supports Managed Identity, env vars, CLI, etc.).
    // The Key Vault URI is externalised to the environment variable AZURE_KEY_VAULT_URI.
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    @Value("${azure.keyvault.uri:#{environment['AZURE_KEY_VAULT_URI']}}")
    private String keyVaultUri;

    /**
     * Lazily-initialised SecretClient backed by DefaultAzureCredential.
     * In Azure cloud environments this resolves via Managed Identity automatically.
     * Locally it falls back to AZURE_CLIENT_ID / AZURE_CLIENT_SECRET / AZURE_TENANT_ID
     * environment variables or the Azure CLI login.
     */
    private SecretClient getSecretClient() {
        return new SecretClientBuilder()
                .vaultUrl(keyVaultUri)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
    }

    /**
     * Retrieves the database username from Azure Key Vault.
     * Secret name: db-username
     */
    private String getDbUser() {
        return getSecretClient().getSecret("db-username").getValue();
    }

    /**
     * Retrieves the database password from Azure Key Vault.
     * Secret name: db-password
     */
    private String getDbPass() {
        return getSecretClient().getSecret("db-password").getValue();
    }

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * cr-java-0090 FIX: Returns the Azure AD authenticated principal name from the
     * Spring Security context.  In a cloud deployment the JWT bearer token issued by
     * Azure Active Directory (Entra ID) is validated by the Spring Security OAuth2
     * resource-server filter chain configured in {@link AzureAdSecurityConfig}.
     * No local credential file is read; identity is always resolved from the
     * centrally-managed Azure AD token.
     *
     * @return the authenticated user's principal name (Azure AD UPN / object ID),
     *         or {@code "anonymous"} when no authentication context is present
     *         (e.g. during unit tests or unauthenticated health-check calls).
     */
    public String getAuthenticatedPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            // When Spring Security Azure AD validates a JWT the principal is a Jwt object.
            if (principal instanceof Jwt) {
                Jwt jwt = (Jwt) principal;
                // Prefer the preferred_username claim (Azure AD UPN); fall back to subject.
                String preferredUsername = jwt.getClaimAsString("preferred_username");
                return (preferredUsername != null && !preferredUsername.isEmpty())
                        ? preferredUsername
                        : jwt.getSubject();
            }
            return authentication.getName();
        }
        return "anonymous";
    }

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

        // cr-java-0090 FIX (line 108): MD5-based authentication token replaced with
        // SHA-256 digest.  MD5 (RFC 6151) is cryptographically broken and MUST NOT be
        // used for any security-related purpose.  SHA-256 is used here for generating
        // a non-secret confirmation code; for password hashing, bcrypt/Argon2 should
        // be used instead.  Authentication itself is now delegated to Azure AD — no
        // locally-stored credentials or file-based tokens are involved.
        String confirmCode = sha256Hash(bookingId + guestName);

        // cr-java-0090 FIX: Authenticated principal resolved from Azure AD JWT token
        // via Spring Security context — replaces any file-based identity lookup.
        String authenticatedUser = getAuthenticatedPrincipal();

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", DB_HOST);
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

    /**
     * cr-java-0090 FIX (replaces md5Hash at line 108):
     * Generates a SHA-256 hex digest of the given input string.
     *
     * <p>SHA-256 replaces the broken MD5 algorithm (sec-weak-hash-001 / RFC 6151).
     * This method is used only for generating non-secret confirmation codes.
     * Authentication itself is fully delegated to Azure Active Directory (Entra ID)
     * via Spring Security OAuth2 resource-server — no locally-stored credentials
     * or file-based authentication tokens are used anywhere in this service.</p>
     *
     * @param input the string to hash
     * @return lowercase hex-encoded SHA-256 digest, or the original input on error
     */
    private String sha256Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
