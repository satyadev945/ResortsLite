package com.demo.resortslite.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration class for AWS Secrets Manager integration.
 * Retrieves database credentials and other sensitive configuration from AWS Secrets Manager
 * instead of hard-coding them in source code or property files.
 */
@Configuration
public class SecretsManagerConfig {

    @Value("${aws.secretsmanager.secret.name:resortslite/database/credentials}")
    private String secretName;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    private Map<String, String> secretCache = new HashMap<>();

    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Retrieves a secret value from AWS Secrets Manager.
     * Secrets are cached in memory to reduce API calls.
     * 
     * @param key The key within the secret JSON (e.g., "username", "password", "host")
     * @return The secret value, or null if not found
     */
    public String getSecretValue(String key) {
        if (secretCache.isEmpty()) {
            loadSecrets();
        }
        return secretCache.get(key);
    }

    /**
     * Loads secrets from AWS Secrets Manager and caches them.
     * The secret is expected to be in JSON format with keys like:
     * {
     *   "host": "db-prod.resorts-internal.com",
     *   "username": "admin",
     *   "password": "Resort$Pass#2019!"
     * }
     */
    private void loadSecrets() {
        try {
            SecretsManagerClient client = secretsManagerClient();
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretString = response.secretString();

            // Parse JSON secret
            ObjectMapper mapper = new ObjectMapper();
            JsonNode secretJson = mapper.readTree(secretString);

            // Cache all key-value pairs
            secretJson.fields().forEachRemaining(entry -> {
                secretCache.put(entry.getKey(), entry.getValue().asText());
            });

        } catch (Exception e) {
            // Log error and fall back to environment variables
            System.err.println("Failed to load secrets from AWS Secrets Manager: " + e.getMessage());
            System.err.println("Falling back to environment variables...");
            
            // Fallback to environment variables for local development
            secretCache.put("host", System.getenv().getOrDefault("DB_HOST", "localhost"));
            secretCache.put("username", System.getenv().getOrDefault("DB_USER", "sa"));
            secretCache.put("password", System.getenv().getOrDefault("DB_PASS", ""));
        }
    }

    /**
     * Refreshes the secret cache by reloading from AWS Secrets Manager.
     * This can be called periodically or on-demand to support secret rotation.
     */
    public void refreshSecrets() {
        secretCache.clear();
        loadSecrets();
    }
}
