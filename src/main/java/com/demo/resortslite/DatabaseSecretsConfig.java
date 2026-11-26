package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import javax.sql.DataSource;

/**
 * Builds the application DataSource using credentials retrieved at startup from
 * AWS Secrets Manager (cr-java-0069). No database credentials are stored in source
 * code or property files.
 *
 * The secret is expected to be a JSON document with "username" and "password" keys
 * (the standard RDS/Secrets Manager format). Its name/ARN is supplied through the
 * DB_SECRET_NAME environment variable (app.db.secret-name).
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class DatabaseSecretsConfig {

    @Value("${app.db.secret-name:}")
    private String secretName;

    @Value("${app.aws.region:us-east-1}")
    private String awsRegion;

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();

        if (secretName != null && !secretName.trim().isEmpty()) {
            try (SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build()) {
                String secretString = client.getSecretValue(
                        GetSecretValueRequest.builder().secretId(secretName).build()).secretString();
                JsonNode json = new ObjectMapper().readTree(secretString);
                ds.setUsername(json.path("username").asText());
                ds.setPassword(json.path("password").asText());
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Unable to load database credentials from AWS Secrets Manager secret: " + secretName, e);
            }
        }
        return ds;
    }
}
