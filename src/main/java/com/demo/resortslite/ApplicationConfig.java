package com.demo.resortslite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * Application configuration for ResortsLite.
 * Configures database transactions and other application-wide settings.
 */
@Configuration
@EnableTransactionManagement
public class ApplicationConfig {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationConfig.class);

    @Value("${spring.application.name}")
    private String applicationName;

    /**
     * Configures the transaction manager for database operations
     */
    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        logger.info("Configuring transaction manager for {}", applicationName);
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * Logs application startup information
     */
    @Bean
    public String applicationInfo() {
        logger.info("=================================================");
        logger.info("Application: {}", applicationName);
        logger.info("Java Version: {}", System.getProperty("java.version"));
        logger.info("Spring Boot Version: 3.2.0");
        logger.info("=================================================");
        return applicationName;
    }
}
