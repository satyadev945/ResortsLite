package com.demo.resortslite.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.annotation.PostConstruct;
import java.util.logging.Logger;

/**
 * AWS Secrets Manager Configuration
 * 
 * This configuration class integrates AWS Secrets Manager to retrieve database credentials
 * securely instead of hard-coding them in source code or property files.
 * 
 * Migration from hard-coded credentials (cr-java-0069):
 * - Removed: DB_USER = "admin" and DB_PASS = "Resort$Pass#2019!" from BookingService.java
 * - Added: AWS Secrets Manager integration for centralized secret management
 * - Benefits: Automatic credential rotation, encrypted storage, audit logging
 * 
 * AWS Secrets Manager Secret Structure:
 * Secret Name: /secret/resortslite/database
 * Secret Value (JSON):
 * {
 *   "url": "jdbc:oracle:thin:@db-prod.resorts-internal.com:1521:ORCL",
 *   "username": "admin",
 *   "password": "Resort$Pass#2019!"
 * }
 * 
 * Environment Variables Required:
 * - AWS_REGION: AWS region where secrets are stored (default: us-east-1)
 * - DB_URL: Injected from Secrets Manager
 * - DB_USERNAME: Injected from Secrets Manager
 * - DB_PASSWORD: Injected from Secrets Manager
 * 
 * IAM Permissions Required:
 * - secretsmanager:GetSecretValue on the secret ARN
 * - kms:Decrypt if using custom KMS key
 */
@Configuration
@Profile("!test")
public class AwsSecretsManagerConfig {

    private static final Logger logger = Logger.getLogger(AwsSecretsManagerConfig.class.getName());

    @Value("${aws.secretsmanager.enabled:false}")
    private boolean secretsManagerEnabled;

    @Value("${aws.secretsmanager.secret-name:/secret/resortslite/database}")
    private String secretName;

    @Value("${aws.secretsmanager.region:us-east-1}")
    private String region;

    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /**
     * Initialize database credentials from AWS Secrets Manager on application startup.
     * This method retrieves the secret and sets system properties that Spring Boot
     * will use for datasource configuration.
     */
    @PostConstruct
    public void initializeDatabaseCredentials() {
        if (!secretsManagerEnabled) {
            logger.info("AWS Secrets Manager integration is disabled. Using default configuration.");
            return;
        }

        try {
            logger.info("Retrieving database credentials from AWS Secrets Manager: " + secretName);
            
            SecretsManagerClient client = secretsManagerClient();
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretString = response.secretString();

            // Parse the JSON secret
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode secretJson = objectMapper.readTree(secretString);

            // Extract database credentials
            String dbUrl = secretJson.get("url").asText();
            String dbUsername = secretJson.get("username").asText();
            String dbPassword = secretJson.get("password").asText();

            // Set system properties for Spring Boot datasource configuration
            System.setProperty("DB_URL", dbUrl);
            System.setProperty("DB_USERNAME", dbUsername);
            System.setProperty("DB_PASSWORD", dbPassword);

            logger.info("Successfully retrieved database credentials from AWS Secrets Manager");
            logger.info("Database URL: " + maskUrl(dbUrl));
            logger.info("Database Username: " + maskUsername(dbUsername));

        } catch (Exception e) {
            logger.severe("Failed to retrieve database credentials from AWS Secrets Manager: " + e.getMessage());
            throw new RuntimeException("Failed to initialize database credentials from AWS Secrets Manager", e);
        }
    }

    /**
     * Mask sensitive parts of the database URL for logging
     */
    private String maskUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "***";
        }
        // Show only the protocol and host, mask the rest
        int atIndex = url.indexOf("@");
        if (atIndex > 0) {
            return url.substring(0, atIndex + 1) + "***";
        }
        return "***";
    }

    /**
     * Mask username for logging (show first and last character only)
     */
    private String maskUsername(String username) {
        if (username == null || username.length() <= 2) {
            return "***";
        }
        return username.charAt(0) + "***" + username.charAt(username.length() - 1);
    }
}
