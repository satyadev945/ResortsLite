package com.demo.resortslite.config;

import com.azure.spring.cloud.autoconfigure.aad.AadResourceServerWebSecurityConfigurerAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Azure Active Directory (Entra ID) Security Configuration
 * 
 * FIXED cr-java-0090: Migrated from file-based authentication to Azure AD with Spring Security
 * 
 * This configuration class integrates Azure Active Directory authentication using:
 * - Microsoft Authentication Library (MSAL) for token validation
 * - Spring Security Azure AD starter for seamless integration
 * - JWT bearer token authentication for stateless API security
 * 
 * Benefits of Azure AD over file-based authentication:
 * - Centralized identity and access management
 * - Single Sign-On (SSO) across applications
 * - Multi-Factor Authentication (MFA) support
 * - Role-based access control (RBAC) with Azure AD groups
 * - Horizontal scalability without shared state
 * - Audit logging and compliance features
 * - Integration with Azure Key Vault for secrets management
 * 
 * Authentication Flow:
 * 1. Client obtains JWT token from Azure AD (OAuth 2.0 / OpenID Connect)
 * 2. Client includes token in Authorization header: "Bearer <token>"
 * 3. Spring Security validates token signature and claims against Azure AD
 * 4. Authenticated user context is available via SecurityContextHolder
 * 
 * Configuration is externalized to application.properties:
 * - spring.cloud.azure.active-directory.tenant-id
 * - spring.cloud.azure.active-directory.credential.client-id
 * - spring.cloud.azure.active-directory.credential.client-secret
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class AzureAdSecurityConfig {

    /**
     * Configure HTTP security with Azure AD JWT bearer token authentication
     * 
     * Security rules:
     * - All /api/** endpoints require authentication
     * - Health check endpoints are public for Azure health probes
     * - Stateless session management (JWT tokens, no server-side sessions)
     * - CSRF disabled for stateless API (tokens provide CSRF protection)
     * 
     * @param http HttpSecurity configuration
     * @return SecurityFilterChain configured for Azure AD authentication
     * @throws Exception if configuration fails
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF for stateless API (JWT tokens provide protection)
            .csrf().disable()
            
            // Configure authorization rules
            .authorizeRequests(authorize -> authorize
                // Public endpoints for health checks (Azure health probes)
                .antMatchers("/actuator/health", "/actuator/info").permitAll()
                
                // All API endpoints require authentication
                .antMatchers("/api/**").authenticated()
                
                // All other requests require authentication by default
                .anyRequest().authenticated()
            )
            
            // Configure OAuth2 resource server with JWT bearer token validation
            .oauth2ResourceServer()
                .jwt();
        
        // Stateless session management (no server-side sessions)
        // Session state is stored in Azure Cache for Redis if needed
        http.sessionManagement()
            .sessionCreationPolicy(SessionCreationPolicy.STATELESS);
        
        return http.build();
    }
}
