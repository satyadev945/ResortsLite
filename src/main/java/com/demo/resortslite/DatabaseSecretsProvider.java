package com.demo.resortslite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * Retrieves database credentials from AWS Secrets Manager.
 * The secret id is supplied through the DB_SECRET_NAME environment variable and the
 * secret is expected to be a JSON document: {"username":"...","password":"...","host":"..."}.
 * Credentials are fetched on demand (and cached briefly) so that rotated secrets are picked up
 * without redeploying the application.
 */
@Component
public class DatabaseSecretsProvider {

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile JsonNode cached;
    private volatile long cachedAt;

    private synchronized JsonNode secret() {
        long now = System.currentTimeMillis();
        if (cached != null && (now - cachedAt) < CACHE_TTL_MS) {
            return cached;
        }
        String secretName = System.getenv("DB_SECRET_NAME");
        if (secretName == null || secretName.isEmpty()) {
            throw new IllegalStateException("DB_SECRET_NAME environment variable is not set");
        }
        try (SecretsManagerClient client = SecretsManagerClient.create()) {
            String json = client.getSecretValue(
                    GetSecretValueRequest.builder().secretId(secretName).build()).secretString();
            cached = objectMapper.readTree(json);
            cachedAt = now;
            return cached;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unable to parse database secret " + secretName, e);
        }
    }

    public String getUsername() {
        return secret().path("username").asText();
    }

    public String getPassword() {
        return secret().path("password").asText();
    }

    public String getHost() {
        String host = secret().path("host").asText(null);
        if (host == null || host.isEmpty()) {
            host = System.getenv("DB_HOST");
        }
        return host;
    }
}
