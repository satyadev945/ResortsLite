package com.demo.resortslite;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Builds the pooled (HikariCP) DataSource with credentials sourced from Azure Key Vault.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(DbCredentialsProvider credentials,
                                 @Value("${spring.datasource.url}") String url,
                                 @Value("${spring.datasource.driver-class-name:}") String driverClassName) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        if (driverClassName != null && !driverClassName.isEmpty()) {
            ds.setDriverClassName(driverClassName);
        }
        ds.setUsername(credentials.getUsername());
        ds.setPassword(credentials.getPassword());
        ds.setInitializationFailTimeout(-1);
        return ds;
    }
}
