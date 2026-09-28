package com.demo.resortslite;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT Token Service for stateless authentication in containerized environments.
 * Replaces server-side session storage to enable horizontal scaling across ECS Fargate tasks.
 * 
 * The JWT signing secret is injected from AWS Secrets Manager via environment variable.
 * This eliminates session affinity requirements and allows any container instance to validate tokens.
 */
@Service
public class JwtTokenService {

    // JWT signing secret injected from AWS Secrets Manager into ECS Fargate task environment
    // Example: aws secretsmanager get-secret-value --secret-id jwt-signing-secret
    @Value("${jwt.secret:default-secret-key-for-development-only-min-256-bits-required}")
    private String jwtSecret;

    @Value("${jwt.expiration:3600000}") // Default: 1 hour in milliseconds
    private long jwtExpiration;

    /**
     * Generate JWT token with user claims (replaces session.setAttribute)
     */
    public String generateToken(String guestName, Map<String, Object> claims) {
        Map<String, Object> tokenClaims = new HashMap<>(claims);
        tokenClaims.put("guestName", guestName);
        
        return Jwts.builder()
                .setClaims(tokenClaims)
                .setSubject(guestName)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Extract all claims from JWT token (replaces session.getAttribute)
     */
    public Claims extractClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * Extract guest name from token
     */
    public String extractGuestName(String token) {
        return extractClaims(token).get("guestName", String.class);
    }

    /**
     * Validate token expiration and signature
     */
    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractClaims(token);
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
