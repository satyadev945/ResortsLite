package com.demo.resortslite;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.Map;

/**
 * cz-java-0063: Stateless JWT utility — signing secret injected from environment variable
 * (JWT_SECRET) which maps to AWS Secrets Manager in ECS Fargate task definition.
 * Replaces server-side HttpSession to support horizontal scaling and container restarts.
 */
@Component
public class JwtUtil {

    private static final long TOKEN_VALIDITY_MS = 3_600_000L; // 1 hour

    // cz-java-0063: JWT signing secret injected via environment variable JWT_SECRET,
    // sourced from AWS Secrets Manager in ECS Fargate task definition.
    @Value("${JWT_SECRET:default-dev-secret-change-in-production}")
    private String jwtSecret;

    private Key getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        // Ensure key is at least 256 bits for HS256
        if (keyBytes.length < 32) {
            keyBytes = java.util.Arrays.copyOf(keyBytes, 32);
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Generate a JWT token embedding the provided claims (e.g. guestName, bookingId).
     */
    public String generateToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + TOKEN_VALIDITY_MS))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Parse and validate a JWT token, returning its claims.
     */
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * Extract a specific claim value from a Bearer token header value.
     */
    public String extractClaim(String bearerToken, String claimKey) {
        if (bearerToken == null || !bearerToken.startsWith("Bearer ")) {
            return null;
        }
        String token = bearerToken.substring(7);
        try {
            Claims claims = parseToken(token);
            Object value = claims.get(claimKey);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
