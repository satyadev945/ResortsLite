package com.demo.resortslite;

import com.azure.core.credential.TokenCredential;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Centralizes cloud-native configuration access for Azure App Configuration and Azure Key Vault.
 * Environment variables remain available as a local/cloud bootstrap fallback so the application
 * can start before managed identity and Azure services are provisioned.
 */
@Service
public class AzureCloudConfigService {

    private final Environment environment;
    private final ConfigurationClient configurationClient;
    private final SecretClient secretClient;

    public AzureCloudConfigService(Environment environment,
                                   @Value("${azure.appconfiguration.endpoint:}") String appConfigurationEndpoint,
                                   @Value("${azure.keyvault.url:}") String keyVaultUrl) {
        this.environment = environment;
        TokenCredential credential = new DefaultAzureCredentialBuilder().build();
        this.configurationClient = StringUtils.hasText(appConfigurationEndpoint)
                ? new ConfigurationClientBuilder()
                .endpoint(appConfigurationEndpoint)
                .credential(credential)
                .buildClient()
                : null;
        this.secretClient = StringUtils.hasText(keyVaultUrl)
                ? new SecretClientBuilder()
                .vaultUrl(keyVaultUrl)
                .credential(credential)
                .buildClient()
                : null;
    }

    public String getConfiguration(String key, String propertyName, String defaultValue) {
        String configuredValue = readFromAppConfiguration(key).orElse(null);
        if (StringUtils.hasText(configuredValue)) {
            return configuredValue;
        }
        return environment.getProperty(propertyName, defaultValue);
    }

    public int getIntConfiguration(String key, String propertyName, int defaultValue) {
        String value = getConfiguration(key, propertyName, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    public String getSecret(String configuredSecretNameProperty, String defaultSecretName, String fallbackPropertyName, String defaultValue) {
        String secretName = environment.getProperty(configuredSecretNameProperty, defaultSecretName);
        if (secretClient != null && StringUtils.hasText(secretName)) {
            try {
                return secretClient.getSecret(secretName).getValue();
            } catch (RuntimeException ignored) {
                // Fall back to externalized application/environment configuration if Key Vault is unavailable.
            }
        }
        return environment.getProperty(fallbackPropertyName, defaultValue);
    }

    private Optional<String> readFromAppConfiguration(String key) {
        if (configurationClient == null || !StringUtils.hasText(key)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(configurationClient.getConfigurationSetting(key, null).getValue());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }
}
