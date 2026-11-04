package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import javax.sql.DataSource;

/**
 * Builds the application DataSource using database credentials retrieved from
 * AWS Secrets Manager. The secret is expected to be a JSON document with
 * "username" and "password" keys (standard RDS secret format).
 *
 * Configure via environment variables:
 *   DB_SECRET_ID  - name or ARN of the secret in AWS Secrets Manager
 *   AWS_REGION    - region (resolved by the AWS SDK default provider chain)
 */
@Configuration
public class DatabaseSecretsConfig {

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.driver-class-name:}")
    private String driverClassName;

    @Value("${app.db.secret-id:}")
    private String secretId;

    @Bean
    @Primary
    public DataSource dataSource() throws Exception {
        if (!StringUtils.hasText(secretId)) {
            throw new IllegalStateException(
                    "DB_SECRET_ID (app.db.secret-id) must reference an AWS Secrets Manager secret "
                            + "containing the database 'username' and 'password'");
        }

        String json;
        try (SecretsManagerClient client = SecretsManagerClient.create()) {
            json = client.getSecretValue(
                    GetSecretValueRequest.builder().secretId(secretId).build()).secretString();
        }
        JsonNode secret = new ObjectMapper().readTree(json);

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        if (StringUtils.hasText(driverClassName)) {
            ds.setDriverClassName(driverClassName);
        }
        ds.setUsername(secret.get("username").asText());
        ds.setPassword(secret.get("password").asText());
        return ds;
    }
}
