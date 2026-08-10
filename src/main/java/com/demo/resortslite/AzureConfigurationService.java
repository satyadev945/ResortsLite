package com.demo.resortslite;

import com.azure.core.credential.TokenCredential;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.data.appconfiguration.models.ConfigurationSetting;
import com.azure.identity.DefaultAzureCredentialBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Centralized Azure App Configuration adapter. Values are resolved from Azure App
 * Configuration when configured, with environment/property fallback for local
 * development and cloud platforms that inject settings as environment variables.
 */
@Service
public class AzureConfigurationService {

    private final Environment environment;
    private final ConfigurationClient configurationClient;

    public AzureConfigurationService(Environment environment,
                                     @Value("${azure.app-configuration.endpoint:}") String endpoint) {
        this.environment = environment;
        if (StringUtils.hasText(endpoint)) {
            TokenCredential credential = new DefaultAzureCredentialBuilder().build();
            this.configurationClient = new ConfigurationClientBuilder()
                    .endpoint(endpoint)
                    .credential(credential)
                    .buildClient();
        } else {
            this.configurationClient = null;
        }
    }

    public String getString(String key, String propertyName, String defaultValue) {
        String azureValue = getAzureSetting(key);
        if (StringUtils.hasText(azureValue)) {
            return azureValue;
        }
        return environment.getProperty(propertyName, defaultValue);
    }

    public int getInt(String key, String propertyName, int defaultValue) {
        String value = getString(key, propertyName, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private String getAzureSetting(String key) {
        if (configurationClient == null || !StringUtils.hasText(key)) {
            return null;
        }
        try {
            ConfigurationSetting setting = configurationClient.getConfigurationSetting(key, null);
            return setting == null ? null : setting.getValue();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
