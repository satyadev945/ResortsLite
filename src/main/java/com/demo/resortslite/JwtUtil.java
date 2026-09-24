package com.demo.resortslite;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.Map;

/**
 * Stateless JWT utility — replaces server-side HttpSession (cz-java-0063).
 *
 * The signing secret is read from the JWT_SECRET environment variable, which
 * must be injected into the ECS Fargate task definition from AWS Secrets Manager.
 * This ensures no session state is stored on any container instance, allowing
 * horizontal scaling and safe container restarts without session loss.
 */
@Component
public class JwtUtil {

    /** Minimum HMAC-SHA256 key length required by jjwt (256 bits / 32 bytes). */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * Resolve the signing key from the JWT_SECRET environment variable.
     * Falls back to a default only for local development; production deployments
     * MUST supply JWT_SECRET via AWS Secrets Manager / ECS task environment.
     */
    private Key signingKey() {
        String secret = System.getenv("JWT_SECRET");
        if (secret == null || secret.isEmpty()) {
            // Fallback for local dev — NOT suitable for production
            secret = "local-dev-secret-replace-in-prod!!";
        }
        // Pad or truncate to exactly MIN_SECRET_BYTES for a valid HMAC-SHA256 key
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            byte[] padded = new byte[MIN_SECRET_BYTES];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Generate a signed JWT token carrying the supplied claims.
     *
     * @param claims  arbitrary key/value pairs to embed in the token payload
     * @param subject the principal identifier (e.g. guestName or bookingId)
     * @param ttlMs   token time-to-live in milliseconds
     * @return compact, URL-safe JWT string
     */
    public String generateToken(Map<String, Object> claims, String subject, long ttlMs) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + ttlMs))
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Parse and validate a JWT token, returning its claims.
     *
     * @param token compact JWT string
     * @return parsed {@link Claims} from the token payload
     * @throws io.jsonwebtoken.JwtException if the token is invalid or expired
     */
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * Extract a single claim value by key from a raw JWT string.
     *
     * @param token JWT compact string
     * @param key   claim key
     * @return claim value as String, or {@code null} if absent
     */
    public String getClaim(String token, String key) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            Claims claims = parseToken(token);
            Object value = claims.get(key);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
