package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Retrieves secrets from Azure Key Vault using DefaultAzureCredential. Environment
 * variables are used as a non-secret local fallback for development and automated tests.
 */
@Service
public class AzureKeyVaultService {

    private final SecretClient secretClient;

    public AzureKeyVaultService(@Value("${azure.keyvault.endpoint:}") String vaultEndpoint) {
        if (vaultEndpoint != null && !vaultEndpoint.trim().isEmpty()) {
            this.secretClient = new SecretClientBuilder()
                    .vaultUrl(vaultEndpoint)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
        } else {
            this.secretClient = null;
        }
    }

    public String getSecret(String secretName, String environmentVariable, String defaultValue) {
        String environmentValue = System.getenv(environmentVariable);
        if (environmentValue != null && !environmentValue.trim().isEmpty()) {
            return environmentValue;
        }

        if (secretClient != null) {
            try {
                String secretValue = secretClient.getSecret(secretName).getValue();
                if (secretValue != null && !secretValue.trim().isEmpty()) {
                    return secretValue;
                }
            } catch (RuntimeException ignored) {
                // Fall through to configured default when Key Vault is not reachable locally.
            }
        }

        return defaultValue;
    }
}
