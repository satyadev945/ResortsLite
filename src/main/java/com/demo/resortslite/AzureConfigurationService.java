package com.demo.resortslite;

import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.data.appconfiguration.models.ConfigurationSetting;
import com.azure.identity.DefaultAzureCredentialBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Centralizes Azure App Configuration lookups with environment-variable fallbacks so
 * the application remains portable across Azure App Service, Container Apps, and tests.
 */
@Service
public class AzureConfigurationService {

    private final ConfigurationClient configurationClient;

    public AzureConfigurationService(@Value("${azure.appconfiguration.endpoint:}") String endpoint) {
        if (endpoint != null && !endpoint.trim().isEmpty()) {
            this.configurationClient = new ConfigurationClientBuilder()
                    .endpoint(endpoint)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
        } else {
            this.configurationClient = null;
        }
    }

    public String getSetting(String key, String environmentVariable, String defaultValue) {
        String environmentValue = System.getenv(environmentVariable);
        if (environmentValue != null && !environmentValue.trim().isEmpty()) {
            return environmentValue;
        }

        if (configurationClient != null) {
            try {
                ConfigurationSetting setting = configurationClient.getConfigurationSetting(key, null);
                if (setting != null && setting.getValue() != null && !setting.getValue().trim().isEmpty()) {
                    return setting.getValue();
                }
            } catch (RuntimeException ignored) {
                // Fall through to default to keep local development and tests resilient when Azure is unavailable.
            }
        }

        return defaultValue;
    }

    public int getIntSetting(String key, String environmentVariable, int defaultValue) {
        String configuredValue = getSetting(key, environmentVariable, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(configuredValue);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
