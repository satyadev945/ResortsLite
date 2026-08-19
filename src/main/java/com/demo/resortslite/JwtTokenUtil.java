package com.demo.resortslite;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * cz-java-0063 FIX: Stateless JWT utility — replaces server-side HttpSession.
 *
 * The signing key is read from the JWT_SIGNING_KEY environment variable, which is
 * injected at runtime by GKE Workload Identity via GCP Secret Manager.  No session
 * state is stored on the server; all guest context travels inside the signed token.
 *
 * GKE / GCP Secret Manager wiring (infrastructure, not code):
 *   1. Store the signing key in GCP Secret Manager as "jwt-signing-key".
 *   2. Bind the GKE Service Account to the secret with roles/secretmanager.secretAccessor.
 *   3. Mount the secret as an env-var JWT_SIGNING_KEY via a SecretProviderClass or
 *      a Kubernetes Secret synced by the Secret Store CSI driver.
 */
@Component
public class JwtTokenUtil {

    // cz-java-0063 FIX: Signing key injected via environment variable.
    // In GKE the value is sourced from GCP Secret Manager through Workload Identity.
    @Value("${JWT_SIGNING_KEY:default-dev-signing-key-replace-in-production}")
    private String signingKeyValue;

    private static final long TOKEN_VALIDITY_MS = 24 * 60 * 60 * 1000L; // 24 hours

    /**
     * Build a signed JWT that carries the guest context previously stored in HttpSession.
     *
     * @param guestName  the authenticated guest name
     * @param bookingId  the booking identifier created in this request
     * @param extraClaims any additional claims to embed (may be empty)
     * @return compact, URL-safe JWT string
     */
    public String generateToken(String guestName, String bookingId, Map<String, Object> extraClaims) {
        SecretKey key = Keys.hmacShaKeyFor(signingKeyValue.getBytes(StandardCharsets.UTF_8));

        return Jwts.builder()
                .setClaims(extraClaims)
                .setSubject(guestName)
                .claim("bookingId", bookingId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + TOKEN_VALIDITY_MS))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Extract all claims from a JWT token.
     *
     * @param token compact JWT string
     * @return parsed Claims
     */
    public Claims extractClaims(String token) {
        SecretKey key = Keys.hmacShaKeyFor(signingKeyValue.getBytes(StandardCharsets.UTF_8));
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * Extract the guest name (JWT subject) from a token.
     *
     * @param token compact JWT string
     * @return guest name
     */
    public String extractGuestName(String token) {
        return extractClaims(token).getSubject();
    }
}
