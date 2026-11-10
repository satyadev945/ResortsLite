package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClientBuilder;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import javax.sql.DataSource;
import java.net.URI;

/**
 * Builds the application DataSource using database credentials retrieved from AWS Secrets Manager.
 * The secret is expected to be a JSON document with "username" and "password" keys
 * (the standard RDS secret format). AWS credentials come from the default provider chain (IAM role).
 */
@Configuration
public class DatabaseSecretsConfig {

    @Value("${app.db.secret-name:}")
    private String secretName;

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username:}")
    private String fallbackUsername;

    @Value("${spring.datasource.password:}")
    private String fallbackPassword;

    @Value("${spring.datasource.driver-class-name:com.microsoft.sqlserver.jdbc.SQLServerDriver}")
    private String driverClassName;

    @Value("${app.aws.region:${AWS_REGION:us-east-1}}")
    private String region;

    @Value("${app.aws.secretsmanager.endpoint:}")
    private String endpoint;

    @Bean
    public DataSource dataSource() {
        String username = fallbackUsername;
        String password = fallbackPassword;

        if (secretName != null && !secretName.trim().isEmpty()) {
            JsonNode secret = fetchSecret(secretName.trim());
            username = secret.path("username").asText(username);
            password = secret.path("password").asText(password);
        }

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName(driverClassName);
        return ds;
    }

    private JsonNode fetchSecret(String id) {
        SecretsManagerClientBuilder builder = SecretsManagerClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.trim().isEmpty()) {
            builder.endpointOverride(URI.create(endpoint.trim()));
        }
        try (SecretsManagerClient client = builder.build()) {
            String json = client.getSecretValue(GetSecretValueRequest.builder().secretId(id).build()).secretString();
            return new ObjectMapper().readTree(json);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unable to parse database secret '" + id + "'", e);
        }
    }
}
