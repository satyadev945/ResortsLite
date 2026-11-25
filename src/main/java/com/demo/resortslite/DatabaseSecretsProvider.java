package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * Retrieves database credentials from AWS Secrets Manager.
 * The secret is expected to be a JSON document with "username" and "password" keys.
 */
public class DatabaseSecretsProvider {

    private final String username;
    private final String password;

    public DatabaseSecretsProvider(String secretName, String region) {
        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.of(region))
                .build()) {
            String secretString = client.getSecretValue(
                    GetSecretValueRequest.builder().secretId(secretName).build()).secretString();
            JsonNode node = new ObjectMapper().readTree(secretString);
            this.username = node.get("username").asText();
            this.password = node.get("password").asText();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to load database credentials from AWS Secrets Manager secret: " + secretName, e);
        }
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }
}
