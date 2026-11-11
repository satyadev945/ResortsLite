package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Retrieves secrets (e.g. database credentials) from Azure Key Vault using
 * DefaultAzureCredential (Managed Identity in Azure, developer login locally).
 * The vault URI is supplied externally via AZURE_KEYVAULT_URI.
 */
@Component
public class KeyVaultSecretProvider {

    private final String vaultUri;
    private volatile SecretClient client;

    public KeyVaultSecretProvider(@Value("${app.keyvault.uri:}") String vaultUri) {
        this.vaultUri = vaultUri;
    }

    public String getSecret(String name) {
        if (vaultUri == null || vaultUri.isEmpty()) {
            throw new IllegalStateException("Azure Key Vault URI is not configured (AZURE_KEYVAULT_URI)");
        }
        return client().getSecret(name).getValue();
    }

    private SecretClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = new SecretClientBuilder()
                            .vaultUrl(vaultUri)
                            .credential(new DefaultAzureCredentialBuilder().build())
                            .buildClient();
                }
            }
        }
        return client;
    }
}
