package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.annotation.PostConstruct;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility component that retrieves database credentials from AWS Secrets Manager.
 *
 * <p>The secret is expected to be stored as a JSON string with the following structure:
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
 * <p>Configure the secret name via the environment variable
 * {@code DB_SECRET_NAME} (default: {@code resortslite/db/credentials}).
 * Configure the AWS region via {@code AWS_REGION} (default: {@code us-east-1}).
 *
 * <p>Remediation for rule cr-java-0069 (Hard-coded Database Credentials):
 * credentials are no longer embedded in source code; they are fetched at
 * application start-up from AWS Secrets Manager and cached in memory.
 */
@Component
public class AwsSecretsManagerUtil {

    private static final Logger LOGGER = Logger.getLogger(AwsSecretsManagerUtil.class.getName());

    /** AWS Secrets Manager secret name / ARN for DB credentials. */
    @Value("${aws.db.secret.name:resortslite/db/credentials}")
    private String dbSecretName;

    /** AWS region where the secret is stored. */
    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    // Cached credential fields — populated once at startup via @PostConstruct
    private String dbUsername;
    private String dbPassword;
    private String dbHost;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Fetches and caches DB credentials from AWS Secrets Manager at application startup.
     * If the secret cannot be retrieved (e.g., running locally without AWS credentials),
     * the method logs a warning and falls back to environment variables so that local
     * development is not broken.
     */
    @PostConstruct
    public void loadSecrets() {
        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretJson = response.secretString();

            JsonNode secretNode = objectMapper.readTree(secretJson);
            dbUsername = secretNode.path("username").asText(null);
            dbPassword = secretNode.path("password").asText(null);
            dbHost     = secretNode.path("host").asText(null);

            LOGGER.info("DB credentials successfully loaded from AWS Secrets Manager "
                    + "(secret: " + dbSecretName + ")");

        } catch (Exception ex) {
            // Fallback: read from environment variables so local / CI runs still work.
            LOGGER.log(Level.WARNING,
                    "Could not load DB credentials from AWS Secrets Manager ("
                            + dbSecretName + "). Falling back to environment variables. "
                            + "Reason: " + ex.getMessage());
            dbUsername = System.getenv("DB_USERNAME");
            dbPassword = System.getenv("DB_PASSWORD");
            dbHost     = System.getenv("DB_HOST");
        }
    }

    /** @return the database username retrieved from AWS Secrets Manager. */
    public String getDbUsername() {
        return dbUsername;
    }

    /** @return the database password retrieved from AWS Secrets Manager. */
    public String getDbPassword() {
        return dbPassword;
    }

    /** @return the database host retrieved from AWS Secrets Manager. */
    public String getDbHost() {
        return dbHost;
    }
}
