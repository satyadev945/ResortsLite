package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Enables Azure Active Directory (Microsoft Entra ID) authentication when configured.
 * This replaces local/file-based authentication assumptions with centralized cloud identity.
 */
@Configuration
public class SecurityConfig {

    @Value("${app.security.azure-ad.enabled:false}")
    private boolean azureAdEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf().disable();
        if (azureAdEnabled) {
            http.authorizeRequests(authorize -> authorize
                    .antMatchers("/actuator/health", "/h2-console/**").permitAll()
                    .anyRequest().authenticated())
                    .oauth2ResourceServer().jwt();
        } else {
            http.authorizeRequests(authorize -> authorize.anyRequest().permitAll());
        }
        http.headers().frameOptions().sameOrigin();
        return http.build();
    }
}
