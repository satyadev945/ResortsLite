package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AzureSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   @Value("${app.security.aad-enabled:false}") boolean aadEnabled) throws Exception {
        if (aadEnabled) {
            http.authorizeRequests()
                    .antMatchers("/api/bookings/**").authenticated()
                    .anyRequest().permitAll()
                    .and()
                    .oauth2Login();
        } else {
            http.authorizeRequests().anyRequest().permitAll();
        }
        http.csrf().disable();
        return http.build();
    }
}
