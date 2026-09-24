package com.demo.resortslite.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

/**
 * AWS Secrets Manager configuration.
 *
 * Replaces hard-coded database credentials (cr-java-0069) with secrets fetched
 * at application startup from AWS Secrets Manager.  The secret is expected to be
 * a JSON object with at least the keys "username" and "password", e.g.:
 *
 * <pre>
 * {
 *   "username": "admin",
 *   "password": "Resort$Pass#2019!",
 *   "host":     "db-prod.resorts-internal.com"
 * }
 * </pre>
 *
 * Override the secret name and AWS region via environment variables:
 *   DB_SECRET_NAME  (default: resortslite/db/credentials)
 *   AWS_REGION      (default: us-east-1)
 */
@Configuration
public class AwsSecretsManagerConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsSecretsManagerConfig.class);

    /** Name of the secret stored in AWS Secrets Manager. */
    @Value("${aws.secretsmanager.secret-name:${DB_SECRET_NAME:resortslite/db/credentials}}")
    private String secretName;

    /** AWS region where the secret is stored. */
    @Value("${cloud.aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Provides a {@link SecretsManagerClient} bean scoped to the configured region.
     * The client uses the default credential provider chain (IAM role, environment
     * variables, ~/.aws/credentials) — no hard-coded credentials required.
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Fetches the database secret from AWS Secrets Manager and exposes its fields
     * as a {@link DbCredentials} value object that other beans can inject.
     *
     * <p>Falls back to environment variables {@code DB_USERNAME} / {@code DB_PASSWORD}
     * / {@code DB_HOST} when the secret cannot be reached (e.g. local development
     * without AWS access).</p>
     */
    @Bean
    public DbCredentials dbCredentials(SecretsManagerClient secretsManagerClient) {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secretJson = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(secretJson);

            String username = node.path("username").asText();
            String password = node.path("password").asText();
            String host     = node.path("host").asText("db-prod.resorts-internal.com");

            log.info("Database credentials loaded from AWS Secrets Manager (secret: {})", secretName);
            return new DbCredentials(username, password, host);

        } catch (Exception ex) {
            // Fallback: read from environment variables so the app can still start
            // in local / CI environments that do not have AWS Secrets Manager access.
            log.warn("Could not retrieve secret '{}' from AWS Secrets Manager — "
                    + "falling back to environment variables DB_USERNAME / DB_PASSWORD / DB_HOST. "
                    + "Reason: {}", secretName, ex.getMessage());

            String username = System.getenv().getOrDefault("DB_USERNAME", "");
            String password = System.getenv().getOrDefault("DB_PASSWORD", "");
            String host     = System.getenv().getOrDefault("DB_HOST", "");
            return new DbCredentials(username, password, host);
        }
    }

    /**
     * Immutable value object that carries the database credentials retrieved from
     * AWS Secrets Manager.  Inject this bean wherever DB_USER / DB_PASS / DB_HOST
     * were previously hard-coded.
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
