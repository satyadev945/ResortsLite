package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * SecurityConfig — Azure Active Directory (Entra ID) Spring Security configuration.
 *
 * <p>cr-java-0090 FIX: Replaces file-based authentication (local credential storage,
 * MD5-hashed tokens) with Azure Active Directory (Entra ID) OAuth 2.0 / OIDC
 * authentication using Microsoft Authentication Library (MSAL) and the
 * Spring Cloud Azure Active Directory starter.</p>
 *
 * <p>Key design decisions:</p>
 * <ul>
 *   <li><strong>OAuth 2.0 Resource Server (JWT)</strong>: The application validates
 *       Azure AD-issued JWT bearer tokens on every request. No session cookies or
 *       local credential files are required — authentication state is carried in the
 *       stateless JWT, enabling horizontal scaling without server affinity.</li>
 *   <li><strong>Stateless session management</strong>: {@code SessionCreationPolicy.STATELESS}
 *       ensures the security layer never creates an HTTP session, complementing the
 *       Redis-backed session store used by the rest of the application.</li>
 *   <li><strong>Permit health / H2 console endpoints</strong>: Actuator health probes
 *       and the H2 development console are permitted without authentication so that
 *       Azure App Service / Container Apps liveness probes continue to work.</li>
 *   <li><strong>All API endpoints require authentication</strong>: Every request to
 *       {@code /api/**} must carry a valid Azure AD JWT bearer token, enforcing
 *       centralised identity management across all distributed instances.</li>
 * </ul>
 *
 * <p>Required environment variables / Azure App Service application settings:</p>
 * <pre>
 *   AZURE_AD_TENANT_ID   — Azure AD tenant ID (e.g. xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)
 *   AZURE_AD_CLIENT_ID   — Application (client) ID registered in Azure AD
 * </pre>
 *
 * <p>These are mapped to {@code spring.cloud.azure.active-directory.profile.tenant-id}
 * and {@code spring.cloud.azure.active-directory.credential.client-id} in
 * {@code application.properties}.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Configures the Spring Security filter chain to use Azure AD JWT bearer token
     * validation as the sole authentication mechanism.
     *
     * <p>All requests to {@code /api/**} require a valid Azure AD JWT.
     * Health check and H2 console endpoints are permitted without authentication
     * to support cloud platform liveness / readiness probes and local development.</p>
     *
     * @param http the {@link HttpSecurity} builder provided by Spring Security
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if the security configuration cannot be applied
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // cr-java-0090 FIX: Stateless session — no local credential files or
            // server-side session tokens. Authentication state is carried in the
            // Azure AD JWT bearer token on every request.
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Disable CSRF for stateless REST API (JWT bearer tokens are CSRF-safe)
            .csrf(csrf -> csrf.disable())

            // Authorization rules
            .authorizeHttpRequests(authz -> authz
                // Permit health probes and H2 console for local development / cloud probes
                .antMatchers("/actuator/health", "/actuator/info", "/h2-console/**").permitAll()
                // All API endpoints require a valid Azure AD JWT bearer token
                .antMatchers("/api/**").authenticated()
                // Permit any other requests (e.g., static resources, root path)
                .anyRequest().permitAll()
            )

            // cr-java-0090 FIX: Configure OAuth 2.0 Resource Server with JWT validation.
            // Spring Cloud Azure AD starter auto-configures the JwtDecoder to validate
            // tokens issued by the Azure AD tenant specified in application.properties.
            // No local credential files, no MD5 hashes — identity is managed by Azure AD.
            .oauth2ResourceServer(oauth2 -> oauth2.jwt())

            // Allow H2 console frames in development (frameOptions must be disabled)
            .headers(headers -> headers.frameOptions().sameOrigin());

        return http.build();
    }
}
