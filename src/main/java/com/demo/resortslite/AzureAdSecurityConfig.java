package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * AzureAdSecurityConfig — Spring Security configuration for Azure Active Directory
 * (Entra ID) OAuth 2.0 / OIDC authentication.
 *
 * <p><strong>cr-java-0090 FIX — File-based Authentication:</strong><br>
 * This configuration class replaces local file-based credential storage with
 * Azure Active Directory (Entra ID) authentication using Microsoft Authentication
 * Library (MSAL) and Spring Security Azure AD integration for centralised,
 * scalable identity management.</p>
 *
 * <p>Key design decisions:
 * <ul>
 *   <li>The application is configured as an OAuth 2.0 <em>resource server</em> that
 *       validates JWT bearer tokens issued by Azure AD.  No credentials are stored
 *       locally — all identity assertions come from the Azure AD token.</li>
 *   <li>The JWT issuer URI and audience are externalised to environment variables
 *       ({@code AZURE_AD_TENANT_ID} and {@code AZURE_AD_CLIENT_ID}) so that the
 *       same binary can be deployed to dev, staging, and production without code
 *       changes.</li>
 *   <li>The session creation policy is set to {@code STATELESS} — HTTP sessions are
 *       not used for authentication state; every request must carry a valid Azure AD
 *       JWT bearer token.</li>
 *   <li>The H2 console and Spring Boot actuator health endpoint are permitted without
 *       authentication to support local development and cloud health probes.</li>
 * </ul>
 * </p>
 *
 * <h3>Required environment variables</h3>
 * <pre>
 *   AZURE_AD_TENANT_ID   — Azure AD tenant ID (GUID)
 *   AZURE_AD_CLIENT_ID   — Application (client) ID registered in Azure AD
 * </pre>
 *
 * <h3>Required application.properties entries (already present)</h3>
 * <pre>
 *   spring.security.oauth2.resourceserver.jwt.issuer-uri=
 *       https://login.microsoftonline.com/${AZURE_AD_TENANT_ID}/v2.0
 *   azure.activedirectory.client-id=${AZURE_AD_CLIENT_ID}
 * </pre>
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class AzureAdSecurityConfig {

    /**
     * Azure AD tenant ID — injected from the {@code AZURE_AD_TENANT_ID} environment
     * variable via {@code application.properties}.
     */
    @Value("${azure.activedirectory.tenant-id:${AZURE_AD_TENANT_ID:common}}")
    private String tenantId;

    /**
     * Azure AD application (client) ID — injected from the {@code AZURE_AD_CLIENT_ID}
     * environment variable via {@code application.properties}.
     */
    @Value("${azure.activedirectory.client-id:${AZURE_AD_CLIENT_ID:}}")
    private String clientId;

    /**
     * JWT issuer URI for Azure AD token validation.
     * Defaults to the Azure AD v2.0 endpoint for the configured tenant.
     */
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:" +
           "https://login.microsoftonline.com/${AZURE_AD_TENANT_ID:common}/v2.0}")
    private String issuerUri;

    /**
     * Configures the Spring Security filter chain for Azure AD JWT bearer token
     * authentication.
     *
     * <p>All API endpoints under {@code /api/**} require a valid Azure AD JWT.
     * The H2 console ({@code /h2-console/**}) and actuator health endpoint
     * ({@code /actuator/health}) are permitted without authentication.</p>
     *
     * @param http the {@link HttpSecurity} builder
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if the security configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // cr-java-0090 FIX: Stateless session — authentication state is carried
            // in the Azure AD JWT bearer token on every request; no server-side
            // HTTP session is created for authentication purposes.
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Configure as OAuth 2.0 resource server validating Azure AD JWTs.
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(jwt -> jwt.decoder(jwtDecoder())))

            // Authorisation rules.
            .authorizeHttpRequests(authz -> authz
                // Allow H2 console for local development.
                .antMatchers("/h2-console/**").permitAll()
                // Allow Spring Boot actuator health probe (used by Azure load balancers).
                .antMatchers("/actuator/health").permitAll()
                // All other requests require a valid Azure AD JWT bearer token.
                .anyRequest().authenticated())

            // Allow H2 console to render in an iframe (local dev only).
            .headers(headers -> headers.frameOptions().sameOrigin())

            // Disable CSRF for stateless REST API (tokens provide CSRF protection).
            .csrf(csrf -> csrf.disable());

        return http.build();
    }

    /**
     * Creates a {@link JwtDecoder} that validates Azure AD JWT tokens.
     *
     * <p>The decoder validates:
     * <ul>
     *   <li>Token signature against Azure AD's JWKS endpoint.</li>
     *   <li>Token expiry ({@link JwtTimestampValidator}).</li>
     *   <li>Audience claim — must match the registered Azure AD application client ID.</li>
     * </ul>
     * </p>
     *
     * @return a configured {@link JwtDecoder} for Azure AD tokens
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder jwtDecoder = (NimbusJwtDecoder)
                JwtDecoders.fromIssuerLocation(issuerUri);

        // Validate the 'aud' claim to ensure the token was issued for this application.
        OAuth2TokenValidator<Jwt> audienceValidator =
                new JwtClaimValidator<>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.toString().contains(clientId));

        OAuth2TokenValidator<Jwt> withTimestamp = new JwtTimestampValidator();

        OAuth2TokenValidator<Jwt> combinedValidator =
                new DelegatingOAuth2TokenValidator<>(withTimestamp, audienceValidator);

        jwtDecoder.setJwtValidator(combinedValidator);
        return jwtDecoder;
    }
}
