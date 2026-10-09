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
 * cr-java-0069 fix: Centralised AWS Secrets Manager configuration.
 *
 * <p>Retrieves database credentials (username / password) from AWS Secrets Manager
 * at application start-up, eliminating hard-coded credentials from source code.
 * The secret is expected to be a JSON object with the keys {@code username} and
 * {@code password}, e.g.:
 * <pre>
 * {
 *   "username": "admin",
 *   "password": "Resort$Pass#2019!"
 * }
 * </pre>
 *
 * <p>Required environment variables / application properties:
 * <ul>
 *   <li>{@code AWS_REGION} (or {@code aws.region}) – AWS region where the secret lives</li>
 *   <li>{@code DB_SECRET_NAME} (or {@code aws.secretsmanager.db-secret-name}) –
 *       the Secrets Manager secret name / ARN</li>
 * </ul>
 */
@Configuration
public class AwsSecretsManagerConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsSecretsManagerConfig.class);

    /** AWS region – injected from {@code AWS_REGION} env-var or {@code aws.region} property. */
    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Name / ARN of the Secrets Manager secret that holds the DB credentials.
     * Injected from {@code DB_SECRET_NAME} env-var or
     * {@code aws.secretsmanager.db-secret-name} property.
     */
    @Value("${aws.secretsmanager.db-secret-name:${DB_SECRET_NAME:resortslite/db/credentials}}")
    private String dbSecretName;

    // -----------------------------------------------------------------------
    // Beans
    // -----------------------------------------------------------------------

    /**
     * Provides a singleton {@link SecretsManagerClient} for the configured region.
     * The client uses the default credential provider chain (IAM role, env-vars, etc.).
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Fetches the DB credentials secret from AWS Secrets Manager and exposes
     * the parsed values as a {@link DbCredentials} record/bean.
     *
     * @param client the {@link SecretsManagerClient} bean
     * @return a {@link DbCredentials} instance populated from the secret
     */
    @Bean
    public DbCredentials dbCredentials(SecretsManagerClient client) {
        log.info("Fetching DB credentials from AWS Secrets Manager: secret='{}'", dbSecretName);
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

            log.info("DB credentials successfully retrieved from AWS Secrets Manager.");
            return new DbCredentials(username, password);

        } catch (Exception ex) {
            log.error("Failed to retrieve DB credentials from AWS Secrets Manager "
                    + "(secret='{}', region='{}'): {}", dbSecretName, awsRegion, ex.getMessage());
            throw new IllegalStateException(
                    "Cannot start application: DB credentials unavailable from AWS Secrets Manager. "
                    + "Ensure the secret '" + dbSecretName + "' exists in region '" + awsRegion
                    + "' and the execution role has secretsmanager:GetSecretValue permission.",
                    ex);
        }
    }

    // -----------------------------------------------------------------------
    // Inner value-holder
    // -----------------------------------------------------------------------

    /**
     * Immutable holder for database credentials retrieved from AWS Secrets Manager.
     * Injected wherever DB username / password are needed (e.g. {@link BookingService}).
     */
    public static final class DbCredentials {

        private final String username;
        private final String password;

        public DbCredentials(String username, String password) {
            this.username = username;
            this.password = password;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }
    }
}
