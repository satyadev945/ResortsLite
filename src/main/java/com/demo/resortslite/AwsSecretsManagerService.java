package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * AWS Secrets Manager Service
 * FIXED cr-java-0090: Replaces file-based authentication with AWS Secrets Manager
 * for secure credential storage and retrieval.
 */
@Service
public class AwsSecretsManagerService {

    @Value("${aws.secretsmanager.secret.name:resortslite/database/credentials}")
    private String secretName;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    private SecretsManagerClient secretsClient;
    private ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        this.secretsClient = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Retrieves a secret from AWS Secrets Manager
     * @param secretName The name or ARN of the secret
     * @return The secret value as a string
     */
    public String getSecret(String secretName) {
        try {
            GetSecretValueRequest getSecretValueRequest = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            GetSecretValueResponse getSecretValueResponse = secretsClient.getSecretValue(getSecretValueRequest);
            return getSecretValueResponse.secretString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve secret from AWS Secrets Manager: " + secretName, e);
        }
    }

    /**
     * Retrieves database credentials from AWS Secrets Manager
     * @return Map containing database credentials (host, username, password)
     */
    public Map<String, String> getDatabaseCredentials() {
        try {
            String secretString = getSecret(secretName);
            JsonNode secretJson = objectMapper.readTree(secretString);
            
            Map<String, String> credentials = new HashMap<>();
            credentials.put("host", secretJson.get("host").asText());
            credentials.put("username", secretJson.get("username").asText());
            credentials.put("password", secretJson.get("password").asText());
            credentials.put("database", secretJson.has("database") ? secretJson.get("database").asText() : "resortdb");
            credentials.put("port", secretJson.has("port") ? secretJson.get("port").asText() : "3306");
            
            return credentials;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse database credentials from AWS Secrets Manager", e);
        }
    }

    /**
     * Retrieves API credentials from AWS Secrets Manager
     * @param apiSecretName The name of the API secret
     * @return Map containing API credentials
     */
    public Map<String, String> getApiCredentials(String apiSecretName) {
        try {
            String secretString = getSecret(apiSecretName);
            JsonNode secretJson = objectMapper.readTree(secretString);
            
            Map<String, String> credentials = new HashMap<>();
            credentials.put("apiKey", secretJson.get("apiKey").asText());
            credentials.put("apiSecret", secretJson.has("apiSecret") ? secretJson.get("apiSecret").asText() : "");
            credentials.put("endpoint", secretJson.has("endpoint") ? secretJson.get("endpoint").asText() : "");
            
            return credentials;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse API credentials from AWS Secrets Manager", e);
        }
    }
}
