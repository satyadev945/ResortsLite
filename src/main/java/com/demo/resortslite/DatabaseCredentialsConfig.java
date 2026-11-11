package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

/**
 * Supplies database credentials from Azure Key Vault using DefaultAzureCredential
 * (managed identity in Azure, developer login locally). No credentials live in source
 * code or property files.
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class DatabaseCredentialsConfig {

    @Value("${azure.keyvault.uri:}")
    private String keyVaultUri;

    @Value("${azure.keyvault.db-username-secret:db-username}")
    private String usernameSecretName;

    @Value("${azure.keyvault.db-password-secret:db-password}")
    private String passwordSecretName;

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        if (StringUtils.hasText(keyVaultUri)) {
            SecretClient client = new SecretClientBuilder()
                    .vaultUrl(keyVaultUri)
                    .credential(new DefaultAzureCredentialBuilder().build())
                    .buildClient();
            properties.setUsername(client.getSecret(usernameSecretName).getValue());
            properties.setPassword(client.getSecret(passwordSecretName).getValue());
        }
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}
