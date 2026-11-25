package com.demo.resortslite;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Builds the pooled DataSource using credentials fetched from AWS Secrets Manager
 * (secret name supplied through DB_SECRET_NAME). No credentials live in source or properties.
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class DataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties,
                                 @Value("${app.db.secret-name:}") String secretName,
                                 @Value("${app.aws.region:us-east-1}") String region) {
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        if (secretName != null && !secretName.trim().isEmpty()) {
            DatabaseSecretsProvider secrets = new DatabaseSecretsProvider(secretName, region);
            ds.setUsername(secrets.getUsername());
            ds.setPassword(secrets.getPassword());
        }
        return ds;
    }
}
