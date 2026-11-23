package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves database credentials from Azure Key Vault using DefaultAzureCredential
 * (managed identity in Azure, developer login locally). Falls back to the
 * spring.datasource.* values (environment variables) only when no vault is configured.
 */
@Component
public class DbCredentialsProvider {

    private final String vaultUri;
    private final String usernameSecret;
    private final String passwordSecret;
    private final String fallbackUsername;
    private final String fallbackPassword;
    private volatile SecretClient secretClient;

    public DbCredentialsProvider(
            @Value("${azure.keyvault.uri:}") String vaultUri,
            @Value("${azure.keyvault.secret.db-username:db-username}") String usernameSecret,
            @Value("${azure.keyvault.secret.db-password:db-password}") String passwordSecret,
            @Value("${spring.datasource.username:}") String fallbackUsername,
            @Value("${spring.datasource.password:}") String fallbackPassword) {
        this.vaultUri = vaultUri;
        this.usernameSecret = usernameSecret;
        this.passwordSecret = passwordSecret;
        this.fallbackUsername = fallbackUsername;
        this.fallbackPassword = fallbackPassword;
    }

    public String getUsername() {
        return resolve(usernameSecret, fallbackUsername);
    }

    public String getPassword() {
        return resolve(passwordSecret, fallbackPassword);
    }

    private String resolve(String secretName, String fallback) {
        if (vaultUri == null || vaultUri.trim().isEmpty()) {
            return fallback;
        }
        return client().getSecret(secretName).getValue();
    }

    private SecretClient client() {
        if (secretClient == null) {
            synchronized (this) {
                if (secretClient == null) {
                    secretClient = new SecretClientBuilder()
                            .vaultUrl(vaultUri)
                            .credential(new DefaultAzureCredentialBuilder().build())
                            .buildClient();
                }
            }
        }
        return secretClient;
    }
}
