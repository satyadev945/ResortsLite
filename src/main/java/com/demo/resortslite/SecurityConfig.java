package com.demo.resortslite;

import com.azure.spring.cloud.autoconfigure.aad.AadResourceServerWebSecurityConfigurerAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * cr-java-0090 FIX: Azure Active Directory (Entra ID) Security Configuration.
 *
 * <p>Replaces local file-based credential storage with Azure Active Directory (Entra ID)
 * authentication using Microsoft Authentication Library (MSAL) and Spring Security
 * Azure AD integration for centralized, scalable identity management.</p>
 *
 * <h3>How it works</h3>
 * <ul>
 *   <li>Configures the application as an OAuth2 Resource Server that validates
 *       Azure AD JWT bearer tokens on every incoming request.</li>
 *   <li>Uses {@link AadResourceServerWebSecurityConfigurerAdapter} provided by
 *       {@code spring-cloud-azure-starter-active-directory} to wire MSAL token
 *       validation against the Azure AD / Entra ID JWKS endpoint automatically.</li>
 *   <li>No local user store, password files, or credential files are used — all
 *       identity decisions are delegated to Azure Active Directory.</li>
 * </ul>
 *
 * <h3>Required Azure AD application registration</h3>
 * <ol>
 *   <li>Register the application in Azure Active Directory (Entra ID):
 *       <pre>az ad app create --display-name "ResortsLite"</pre></li>
 *   <li>Expose an API scope (e.g. {@code api://&lt;client-id&gt;/Bookings.Read}).</li>
 *   <li>Set the following environment variables in your Azure environment
 *       (App Service → Configuration, Container Apps env vars, or AKS secrets):
 *       <ul>
 *         <li>{@code AZURE_ACTIVEDIRECTORY_TENANT_ID} – Azure AD tenant ID</li>
 *         <li>{@code AZURE_ACTIVEDIRECTORY_CLIENT_ID} – Application (client) ID</li>
 *         <li>{@code AZURE_ACTIVEDIRECTORY_CLIENT_SECRET} – Client secret
 *             (store in Azure Key Vault; inject via Managed Identity reference)</li>
 *         <li>{@code AZURE_ACTIVEDIRECTORY_APP_ID_URI} – Application ID URI
 *             (e.g. {@code api://&lt;client-id&gt;})</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h3>Calling the API</h3>
 * <p>Clients must obtain a bearer token from Azure AD and include it in the
 * {@code Authorization: Bearer &lt;token&gt;} header of every request.</p>
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig extends AadResourceServerWebSecurityConfigurerAdapter {

    /**
     * Configures the Spring Security HTTP filter chain to:
     * <ul>
     *   <li>Require a valid Azure AD JWT bearer token for all {@code /api/**} endpoints.</li>
     *   <li>Permit unauthenticated access to the H2 console and actuator health endpoint
     *       for local development and operational monitoring.</li>
     *   <li>Disable CSRF for stateless REST API usage (tokens provide CSRF protection).</li>
     * </ul>
     *
     * <p>Token validation (signature, expiry, issuer, audience) is performed automatically
     * by the {@code spring-cloud-azure-starter-active-directory} library using the
     * Azure AD JWKS endpoint — no local credential files or password stores are needed.</p>
     */
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        super.configure(http);
        http
            // cr-java-0090: Disable CSRF — stateless JWT bearer token flow does not
            // require CSRF protection; tokens themselves prevent cross-site request forgery.
            .csrf().disable()
            .authorizeRequests(requests -> requests
                // Allow H2 console for local development (not exposed in production)
                .antMatchers("/h2-console/**").permitAll()
                // Allow Spring Boot Actuator health endpoint for Azure health probes
                .antMatchers("/actuator/health").permitAll()
                // All booking API endpoints require a valid Azure AD bearer token
                .antMatchers("/api/bookings/**").authenticated()
                // Any other request also requires authentication
                .anyRequest().authenticated()
            )
            // cr-java-0090: Allow H2 console frames in local dev (X-Frame-Options)
            .headers().frameOptions().sameOrigin();
    }
}
