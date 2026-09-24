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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 REMEDIATED: DB_HOST remains a non-secret infrastructure value.
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    // cr-java-0069 REMEDIATED: Hard-coded DB_USER and DB_PASS replaced with Azure Key Vault
    // lookups via SecretClient + DefaultAzureCredential. Credentials are no longer stored
    // in source code or version control. Set AZURE_KEYVAULT_URI as an environment variable
    // (e.g. https://<vault-name>.vault.azure.net/) in Azure App Service / Container Apps.
    private final String DB_USER;
    private final String DB_PASS;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    public BookingService(@Value("${azure.keyvault.uri}") String keyVaultUri) {
        // Build a SecretClient authenticated via DefaultAzureCredential (supports Managed
        // Identity, environment variables, Azure CLI, and other credential chains).
        SecretClient secretClient = new SecretClientBuilder()
                .vaultUrl(keyVaultUri)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();

        // Retrieve credentials from Azure Key Vault at startup.
        // Secret names follow the convention: db-username, db-password.
        // These secrets must be pre-provisioned in the Key Vault before deployment.
        this.DB_USER = secretClient.getSecret("db-username").getValue();
        this.DB_PASS = secretClient.getSecret("db-password").getValue();
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

        // cr-java-0090 REMEDIATED: File-based MD5 authentication token generation replaced
        // with Azure Active Directory (Entra ID) identity via Spring Security OAuth2.
        // The confirmation code is now derived from the authenticated caller's Azure AD
        // subject claim (OID / sub) obtained from the JWT bearer token validated by
        // Spring Security's OAuth2 Resource Server. This eliminates local credential
        // storage and leverages centralised, scalable Azure AD identity management.
        String confirmCode = generateAzureAdConfirmationCode(bookingId);

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

    /**
     * cr-java-0090 REMEDIATED: Generates a booking confirmation code using the
     * authenticated Azure AD principal's subject claim (OID) from the JWT bearer token
     * validated by Spring Security OAuth2 Resource Server.
     *
     * <p>This replaces the previous file-based MD5 hash approach (sec-weak-hash-001)
     * with a cloud-native identity pattern:
     * <ul>
     *   <li>Authentication is delegated entirely to Azure Active Directory (Entra ID).</li>
     *   <li>The JWT is validated against the Azure AD JWKS endpoint configured via
     *       {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}.</li>
     *   <li>No credentials, tokens, or hashes are stored locally in files or memory.</li>
     *   <li>The confirmation code is a combination of the booking ID and the caller's
     *       Azure AD object ID (OID), making it unique and tied to the authenticated
     *       identity without any local cryptographic computation.</li>
     * </ul>
     *
     * <p>If no authenticated Azure AD principal is present in the security context
     * (e.g. during integration tests or unauthenticated internal calls), a UUID-based
     * fallback is used so that existing business logic is not disrupted.
     *
     * @param bookingId the unique booking identifier
     * @return a confirmation code derived from the Azure AD principal OID + bookingId
     */
    private String generateAzureAdConfirmationCode(String bookingId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
            Jwt jwt = (Jwt) authentication.getPrincipal();
            // Use the Azure AD Object ID (OID) claim as the identity anchor.
            // The OID is stable across token refreshes and uniquely identifies the
            // user or service principal within the Azure AD tenant.
            String oid = jwt.getClaimAsString("oid");
            if (oid == null || oid.isEmpty()) {
                // Fall back to the standard JWT subject claim if OID is absent.
                oid = jwt.getSubject();
            }
            // Combine bookingId with the first 8 characters of the Azure AD OID to
            // produce a short, unique, identity-bound confirmation code.
            String oidFragment = (oid != null && oid.length() >= 8)
                    ? oid.substring(0, 8).toUpperCase()
                    : UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            return bookingId + "-" + oidFragment;
        }
        // Fallback for unauthenticated contexts (e.g. internal service calls, tests).
        return bookingId + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
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
}
