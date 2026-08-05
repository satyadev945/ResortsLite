package com.demo.resortslite;

import com.azure.spring.cloud.autoconfigure.aad.AadResourceServerWebSecurityConfigurerAdapter;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * cr-java-0090 FIX: Azure Active Directory (Entra ID) Spring Security configuration.
 *
 * <p>Replaces file-based authentication (local credential files, MD5-hashed tokens stored
 * in-process) with centralized, cloud-native identity management via Azure AD.
 *
 * <p>Authentication flow:
 * <ol>
 *   <li>Clients obtain a Bearer JWT token from Azure AD (Entra ID) using OAuth2/OIDC.</li>
 *   <li>Every API request must include the token in the {@code Authorization: Bearer <token>}
 *       header.</li>
 *   <li>Spring Security validates the token signature against Azure AD's JWKS endpoint
 *       ({@code https://login.microsoftonline.com/{tenant-id}/discovery/v2.0/keys}).</li>
 *   <li>No credentials, user data, or security tokens are stored in local files or
 *       in-process memory — all identity state lives in Azure AD.</li>
 * </ol>
 *
 * <p>Required application properties (set via environment variables or Azure App Configuration):
 * <pre>
 *   spring.cloud.azure.active-directory.enabled=true
 *   spring.cloud.azure.active-directory.credential.client-id=${AZURE_AD_CLIENT_ID}
 *   spring.cloud.azure.active-directory.credential.client-secret=${AZURE_AD_CLIENT_SECRET}
 *   spring.cloud.azure.active-directory.profile.tenant-id=${AZURE_AD_TENANT_ID}
 * </pre>
 */
@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig extends AadResourceServerWebSecurityConfigurerAdapter {

    /**
     * Configures HTTP security to require Azure AD JWT Bearer token authentication
     * for all API endpoints, while permitting access to the H2 console and
     * Spring Boot actuator health endpoint for operational purposes.
     *
     * <p>All authentication decisions are delegated to Azure Active Directory (Entra ID):
     * no credentials, user records, or security tokens are stored in local files.
     *
     * @param http the {@link HttpSecurity} to configure
     * @throws Exception if an error occurs during configuration
     */
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        // Apply Azure AD resource-server defaults (JWT Bearer token validation)
        super.configure(http);

        http
            // cr-java-0090: Disable CSRF for stateless REST API (tokens replace session cookies)
            .csrf().disable()
            .authorizeRequests(authorize -> authorize
                // Allow H2 console access for development (restrict in production)
                .antMatchers("/h2-console/**").permitAll()
                // Allow actuator health endpoint without authentication
                .antMatchers("/actuator/health").permitAll()
                // All booking API endpoints require a valid Azure AD JWT Bearer token
                .antMatchers("/api/bookings/**").authenticated()
                // All other requests require authentication via Azure AD
                .anyRequest().authenticated()
            )
            // Allow H2 console to render in frames (development only)
            .headers().frameOptions().sameOrigin();
    }
}
