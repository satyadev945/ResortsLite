package com.demo.resortslite;

import com.azure.spring.aad.webapp.AADWebSecurityConfigurerAdapter;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

@Configuration
public class AzureAdSecurityConfig extends AADWebSecurityConfigurerAdapter {

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        super.configure(http);
        http
                .authorizeRequests()
                .antMatchers("/", "/error").permitAll()
                .anyRequest().authenticated()
                .and()
                .oauth2Login();
    }
}
