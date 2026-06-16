package com.demo.resortslite.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
            .csrf().disable() // Disable CSRF for API endpoints to support React frontend
            .cors() // Enable CORS
            .and()
            .authorizeRequests()
                .antMatchers("/api/auth/login", "/api/auth/logout", "/api/bookings/**", "/h2-console/**").permitAll()
                .antMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().permitAll()
            .and()
            .httpBasic().disable()
            .formLogin().disable()
            .logout()
                .logoutUrl("/api/auth/logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID");

        // Allow H2 console frames
        http.headers().frameOptions().disable();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Using NoOpPasswordEncoder for demo purposes since passwords are managed in AWS Secrets Manager
        // In production, use BCryptPasswordEncoder or similar
        return NoOpPasswordEncoder.getInstance();
    }
}
