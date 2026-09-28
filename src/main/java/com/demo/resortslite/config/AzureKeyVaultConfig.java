package com.demo.resortslite.config;

import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * Azure Key Vault Configuration for secure credential management.
 * 
 * This configuration class integrates with Azure Key Vault to retrieve database credentials
 * and other sensitive configuration values at runtime using DefaultAzureCredential.
 * 
 * DefaultAzureCredential supports multiple authentication methods in the following order:
 * 1. Environment variables (AZURE_CLIENT_ID, AZURE_TENANT_ID, AZURE_CLIENT_SECRET)
 * 2. Managed Identity (when deployed to Azure App Service, Container Apps, AKS, etc.)
 * 3. Azure CLI credentials (for local development)
 * 4. IntelliJ/VS Code Azure plugins
 * 
 * Required Azure Key Vault secrets:
 * - db-username: Database username
 * - db-password: Database password
 * - db-host: Database hostname (optional, can use default)
 * 
 * Environment variables required:
 * - AZURE_KEYVAULT_URI: Azure Key Vault URI (e.g., https://resorts-keyvault.vault.azure.net/)
 * - AZURE_KEYVAULT_ENABLED: Enable/disable Key Vault integration (default: true)
 */
@Configuration
@ConditionalOnProperty(name = "azure.keyvault.enabled", havingValue = "true", matchIfMissing = true)
public class AzureKeyVaultConfig {

    @Value("${azure.keyvault.uri}")
    private String keyVaultUri;

    private final ConfigurableEnvironment environment;

    public AzureKeyVaultConfig(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    /**
     * Creates Azure Key Vault SecretClient using DefaultAzureCredential.
     * 
     * @return SecretClient configured with Key Vault URI and DefaultAzureCredential
     */
    @Bean
    public SecretClient secretClient() {
        DefaultAzureCredential credential = new DefaultAzureCredentialBuilder()
                .build();

        return new SecretClientBuilder()
                .vaultUrl(keyVaultUri)
                .credential(credential)
                .buildClient();
    }

    /**
     * Loads database credentials from Azure Key Vault and injects them into Spring Environment.
     * 
     * This method runs after bean construction and retrieves secrets from Azure Key Vault,
     * making them available as environment variables for Spring's property resolution.
     */
    @PostConstruct
    public void loadSecretsFromKeyVault() {
        try {
            SecretClient client = secretClient();
            Map<String, Object> secrets = new HashMap<>();

            // Retrieve database credentials from Azure Key Vault
            // Secret names in Key Vault use hyphens, converted to environment variable format
            try {
                String dbUsername = client.getSecret("db-username").getValue();
                secrets.put("DB_USERNAME", dbUsername);
            } catch (Exception e) {
                System.err.println("Warning: Failed to retrieve db-username from Key Vault: " + e.getMessage());
            }

            try {
                String dbPassword = client.getSecret("db-password").getValue();
                secrets.put("DB_PASSWORD", dbPassword);
            } catch (Exception e) {
                System.err.println("Warning: Failed to retrieve db-password from Key Vault: " + e.getMessage());
            }

            try {
                String dbHost = client.getSecret("db-host").getValue();
                secrets.put("DB_HOST", dbHost);
            } catch (Exception e) {
                System.err.println("Warning: Failed to retrieve db-host from Key Vault, using default: " + e.getMessage());
            }

            // Add secrets to Spring Environment as a new property source
            if (!secrets.isEmpty()) {
                MapPropertySource propertySource = new MapPropertySource("azureKeyVault", secrets);
                environment.getPropertySources().addFirst(propertySource);
                System.out.println("Successfully loaded " + secrets.size() + " secrets from Azure Key Vault");
            }

        } catch (Exception e) {
            System.err.println("Error loading secrets from Azure Key Vault: " + e.getMessage());
            System.err.println("Application will use default/fallback values from application.properties");
            // Don't fail application startup if Key Vault is unavailable
            // This allows local development without Azure Key Vault access
        }
    }
}
