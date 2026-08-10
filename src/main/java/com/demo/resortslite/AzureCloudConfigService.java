package com.demo.resortslite;

import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.data.appconfiguration.models.ConfigurationSetting;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Centralized Azure App Configuration access with environment-variable fallback
 * for local development and fail-safe cloud startup.
 */
@Service
public class AzureCloudConfigService {

    private final ConfigurationClient configurationClient;
    private final String label;

    public AzureCloudConfigService(
            @Value("${azure.appconfig.endpoint:}") String endpoint,
            @Value("${azure.appconfig.label:}") String label) {
        this.label = StringUtils.hasText(label) ? label : null;
        if (StringUtils.hasText(endpoint)) {
            this.configurationClient = new ConfigurationClientBuilder()
                    .endpoint(endpoint)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
        } else {
            this.configurationClient = null;
        }
    }

    public String getString(String key, String environmentVariableName, String defaultValue) {
        String configuredValue = getFromAzureAppConfiguration(key);
        if (StringUtils.hasText(configuredValue)) {
            return configuredValue;
        }

        String environmentValue = System.getenv(environmentVariableName);
        if (StringUtils.hasText(environmentValue)) {
            return environmentValue;
        }

        return defaultValue;
    }

    public int getInt(String key, String environmentVariableName, int defaultValue) {
        String value = getString(key, environmentVariableName, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private String getFromAzureAppConfiguration(String key) {
        if (configurationClient == null || !StringUtils.hasText(key)) {
            return null;
        }
        try {
            ConfigurationSetting setting = configurationClient.getConfigurationSetting(key, label);
            return setting == null ? null : setting.getValue();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
