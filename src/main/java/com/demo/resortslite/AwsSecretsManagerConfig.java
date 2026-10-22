package com.demo.resortslite;

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
 * AWS Secrets Manager configuration for database credentials.
 *
 * <p>Replaces hard-coded DB_USER / DB_PASS constants (cr-java-0069) with
 * credentials fetched at startup from AWS Secrets Manager.  The secret is
 * expected to be a JSON object with at least the keys {@code username} and
 * {@code password}, e.g.:
 * <pre>
 * {
 *   "username": "admin",
 *   "password": "Resort$Pass#2019!",
 *   "host":     "db-prod.resorts-internal.com",
 *   "port":     "1521",
 *   "dbname":   "ORCL"
 * }
 * </pre>
 *
 * <p>Required environment variables / application properties:
 * <ul>
 *   <li>{@code AWS_REGION} or {@code cloud.aws.region} – AWS region (default: us-east-1)</li>
 *   <li>{@code DB_SECRET_NAME} – name / ARN of the Secrets Manager secret
 *       (default: resortslite/db/credentials)</li>
 * </ul>
 */
@Configuration
public class AwsSecretsManagerConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsSecretsManagerConfig.class);

    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    @Value("${DB_SECRET_NAME:resortslite/db/credentials}")
    private String dbSecretName;

    /**
     * Provides a {@link SecretsManagerClient} bean scoped to the configured AWS region.
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Fetches the database credentials secret from AWS Secrets Manager and returns
     * a {@link DbCredentials} value object consumed by {@link BookingService}.
     *
     * @param client the {@link SecretsManagerClient} bean
     * @return populated {@link DbCredentials}
     */
    @Bean
    public DbCredentials dbCredentials(SecretsManagerClient client) {
        log.info("Fetching DB credentials from AWS Secrets Manager: secret={}, region={}",
                dbSecretName, awsRegion);
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();
            GetSecretValueResponse response = client.getSecretValue(request);
            String secretJson = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(secretJson);

            String username = node.path("username").asText();
            String password = node.path("password").asText();
            String host     = node.path("host").asText("db-prod.resorts-internal.com");

            log.info("DB credentials successfully loaded from Secrets Manager (host={})", host);
            return new DbCredentials(username, password, host);

        } catch (Exception ex) {
            log.error("Failed to retrieve DB credentials from AWS Secrets Manager: {}", ex.getMessage(), ex);
            throw new IllegalStateException(
                    "Cannot start application: DB credentials unavailable from Secrets Manager. "
                    + "Ensure the secret '" + dbSecretName + "' exists in region '" + awsRegion + "'.", ex);
        }
    }

    /**
     * Immutable value object holding database credentials retrieved from AWS Secrets Manager.
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
