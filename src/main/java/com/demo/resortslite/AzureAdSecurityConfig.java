package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * cr-java-0090 REMEDIATED: Azure Active Directory (Entra ID) Spring Security configuration.
 *
 * <p>Replaces file-based authentication (local credential files, MD5 token generation)
 * with Azure AD OAuth2 / OIDC using Spring Security's OAuth2 Resource Server support.
 * All incoming requests to protected API endpoints must carry a valid Azure AD JWT
 * bearer token issued by the configured tenant.
 *
 * <p>Configuration properties (set via environment variables in Azure App Service /
 * Container Apps application settings):
 * <ul>
 *   <li>{@code AZURE_AD_TENANT_ID}  – Azure AD tenant ID (GUID)</li>
 *   <li>{@code AZURE_AD_CLIENT_ID}  – Application (client) ID registered in Azure AD</li>
 * </ul>
 *
 * <p>These values are consumed by
 * {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} and
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} in
 * {@code application.properties}, which Spring Security uses to:
 * <ol>
 *   <li>Fetch the Azure AD public JWKS to validate JWT signatures.</li>
 *   <li>Verify the {@code iss} (issuer) claim matches the configured Azure AD tenant.</li>
 *   <li>Verify the {@code aud} (audience) claim matches the registered application.</li>
 * </ol>
 *
 * <p>Session management is set to STATELESS so that no server-side HTTP session is
 * created — authentication state is carried entirely in the JWT bearer token, enabling
 * stateless horizontal scaling across multiple Azure App Service / Container Apps instances.
 */
@Configuration
@EnableWebSecurity
public class AzureAdSecurityConfig {

    /**
     * Configures the Spring Security filter chain to:
     * <ul>
     *   <li>Require a valid Azure AD JWT bearer token for all {@code /api/**} endpoints.</li>
     *   <li>Permit unauthenticated access to the H2 console and actuator health endpoint
     *       for operational convenience (restrict further in production as needed).</li>
     *   <li>Use stateless session management — no {@code HttpSession} is created.</li>
     *   <li>Validate JWTs using the Azure AD JWKS URI configured in
     *       {@code application.properties}.</li>
     * </ul>
     *
     * @param http the {@link HttpSecurity} builder provided by Spring Security
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if the security configuration cannot be applied
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // cr-java-0090: Disable CSRF for stateless REST API secured by Azure AD JWT.
            // CSRF protection is not required when authentication is performed via
            // short-lived bearer tokens rather than browser cookies.
            .csrf().disable()

            // cr-java-0090: Stateless session — no server-side HttpSession is created.
            // Authentication state is carried entirely in the Azure AD JWT bearer token.
            .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()

            // cr-java-0090: Authorization rules.
            // All /api/** endpoints require a valid Azure AD JWT bearer token.
            // H2 console and health check are permitted for operational access.
            .authorizeRequests()
                .antMatchers("/h2-console/**").permitAll()
                .antMatchers("/actuator/health").permitAll()
                .antMatchers("/api/**").authenticated()
                .anyRequest().authenticated()
            .and()

            // cr-java-0090: Configure Spring Security as an OAuth2 Resource Server.
            // JWTs are validated against the Azure AD JWKS endpoint specified by
            // spring.security.oauth2.resourceserver.jwt.jwk-set-uri in application.properties.
            // The issuer URI is verified against the Azure AD tenant configured via
            // spring.security.oauth2.resourceserver.jwt.issuer-uri.
            .oauth2ResourceServer()
                .jwt();

        // Allow H2 console frames (development only — disable in production).
        http.headers().frameOptions().sameOrigin();

        return http.build();
    }
}
