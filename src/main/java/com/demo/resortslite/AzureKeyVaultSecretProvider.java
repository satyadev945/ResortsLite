package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Retrieves secrets from Azure Key Vault using managed identity / DefaultAzureCredential.
 */
@Service
public class AzureKeyVaultSecretProvider {

    private final SecretClient secretClient;

    public AzureKeyVaultSecretProvider(@Value("${azure.keyvault.endpoint:}") String keyVaultEndpoint) {
        if (StringUtils.hasText(keyVaultEndpoint)) {
            this.secretClient = new SecretClientBuilder()
                    .vaultUrl(keyVaultEndpoint)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
        } else {
            this.secretClient = null;
        }
    }

    public String getSecret(String secretName, String environmentVariableName, String defaultValue) {
        if (secretClient != null && StringUtils.hasText(secretName)) {
            try {
                String value = secretClient.getSecret(secretName).getValue();
                if (StringUtils.hasText(value)) {
                    return value;
                }
            } catch (RuntimeException ex) {
                // Fall through to environment variable fallback so local/dev startup remains possible.
            }
        }

        String environmentValue = System.getenv(environmentVariableName);
        return StringUtils.hasText(environmentValue) ? environmentValue : defaultValue;
    }
}
