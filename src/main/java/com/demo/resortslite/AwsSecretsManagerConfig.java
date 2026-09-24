package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.util.logging.Logger;

/**
 * AWS Secrets Manager configuration for database credentials.
 *
 * Remediation for cr-java-0069 (Hard-coded Database Credentials):
 * Replaces hard-coded DB_USER and DB_PASS constants with credentials
 * fetched at runtime from AWS Secrets Manager, enabling:
 *   - Automatic credential rotation without redeployment
 *   - Encrypted storage of secrets
 *   - Audit trail for secret access
 *   - Compliance with cloud security requirements
 *
 * Secret format expected in AWS Secrets Manager (JSON):
 *   { "username": "...", "password": "...", "host": "..." }
 *
 * Configure via environment variables:
 *   AWS_REGION                  - AWS region (default: us-east-1)
 *   DB_SECRET_NAME              - Secrets Manager secret name/ARN
 *                                 (default: resortslite/db/credentials)
 */
@Configuration
public class AwsSecretsManagerConfig {

    private static final Logger logger = Logger.getLogger(AwsSecretsManagerConfig.class.getName());

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${aws.secretsmanager.secret-name:resortslite/db/credentials}")
    private String secretName;

    /**
     * Provides a SecretsManagerClient bean for use across the application.
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Fetches the database credentials secret from AWS Secrets Manager
     * and returns a parsed DbCredentials object.
     *
     * Falls back to environment variables DB_USERNAME / DB_PASSWORD if
     * the secret cannot be retrieved (e.g., local development without AWS access).
     */
    @Bean
    public DbCredentials dbCredentials(SecretsManagerClient secretsManagerClient) {
        // Attempt to load from AWS Secrets Manager first
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secretString = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode secretJson = mapper.readTree(secretString);

            String username = secretJson.has("username") ? secretJson.get("username").asText() : null;
            String password = secretJson.has("password") ? secretJson.get("password").asText() : null;
            String host     = secretJson.has("host")     ? secretJson.get("host").asText()     : null;

            logger.info("Database credentials successfully loaded from AWS Secrets Manager: " + secretName);
            return new DbCredentials(username, password, host);

        } catch (Exception e) {
            // Fallback: read from environment variables for local/non-AWS environments
            logger.warning("Could not retrieve secret from AWS Secrets Manager ("
                    + e.getMessage() + "). Falling back to environment variables.");
            String username = System.getenv("DB_USERNAME");
            String password = System.getenv("DB_PASSWORD");
            String host     = System.getenv("DB_HOST");
            return new DbCredentials(username, password, host);
        }
    }

    /**
     * Immutable value object holding database credentials retrieved from
     * AWS Secrets Manager (or environment variable fallback).
     */
    public static class DbCredentials {
        private final String username;
        private final String password;
        private final String host;

        public DbCredentials(String username, String password, String host) {
            this.username = username;
            this.password = password;
            this.host     = host;
        }

        public String getUsername() { return username; }
        public String getPassword() { return password; }
        public String getHost()     { return host; }
    }
}
