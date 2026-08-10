package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Azure Entra ID (Azure AD) integration point. When AZURE_AD_ENABLED=true and
 * issuer/client settings are supplied, requests are validated as OAuth2 JWTs.
 * Local development can explicitly leave Azure AD disabled without file-based
 * credential stores or local authentication files.
 */
@Configuration
public class SecurityConfig {

    @Value("${spring.cloud.azure.active-directory.enabled:false}")
    private boolean azureAdEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf().disable();
        if (azureAdEnabled) {
            http.authorizeRequests().anyRequest().authenticated()
                    .and()
                    .oauth2ResourceServer().jwt();
        } else {
            http.authorizeRequests().anyRequest().permitAll();
        }
        return http.build();
    }
}
