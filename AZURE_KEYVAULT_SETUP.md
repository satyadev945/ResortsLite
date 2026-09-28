# Azure Key Vault Integration for ResortsLite

## Overview

This application has been migrated to use Azure Key Vault for secure credential management, eliminating hard-coded database credentials from source code.

## What Changed

### 1. Removed Hard-Coded Credentials
- **Before**: Database credentials (username, password, hostname) were hard-coded in `BookingService.java` (lines 22-23)
- **After**: Credentials are retrieved from Azure Key Vault at runtime using `DefaultAzureCredential`

### 2. Added Azure Key Vault Dependencies
Added to `pom.xml`:
- `azure-security-keyvault-secrets` (v4.6.0) - Azure Key Vault SDK
- `azure-identity` (v1.10.0) - Azure authentication with DefaultAzureCredential

### 3. Created Azure Key Vault Configuration
- New class: `com.demo.resortslite.config.AzureKeyVaultConfig`
- Automatically loads secrets from Azure Key Vault on application startup
- Injects secrets into Spring Environment for property resolution

### 4. Updated Application Properties
- Removed hard-coded credentials from `application.properties`
- Added Azure Key Vault URI configuration
- Database properties now reference environment variables populated by Key Vault

## Azure Key Vault Setup

### Required Secrets in Azure Key Vault

Create the following secrets in your Azure Key Vault:

| Secret Name | Description | Example Value |
|-------------|-------------|---------------|
| `db-username` | Database username | `admin` |
| `db-password` | Database password | `SecurePassword123!` |
| `db-host` | Database hostname (optional) | `db-prod.resorts-internal.com` |

### Azure Key Vault Creation

```bash
# Create Azure Key Vault
az keyvault create \
  --name resorts-keyvault \
  --resource-group resorts-rg \
  --location eastus

# Add database credentials as secrets
az keyvault secret set \
  --vault-name resorts-keyvault \
  --name db-username \
  --value "admin"

az keyvault secret set \
  --vault-name resorts-keyvault \
  --name db-password \
  --value "YourSecurePassword"

az keyvault secret set \
  --vault-name resorts-keyvault \
  --name db-host \
  --value "db-prod.resorts-internal.com"
```

## Authentication Methods

The application uses `DefaultAzureCredential`, which supports multiple authentication methods:

### 1. Managed Identity (Production - Recommended)
When deployed to Azure services (App Service, Container Apps, AKS):
- No configuration needed
- Azure automatically provides identity
- Most secure option for production

```bash
# Enable Managed Identity for Azure App Service
az webapp identity assign \
  --name resorts-app \
  --resource-group resorts-rg

# Grant Key Vault access to Managed Identity
az keyvault set-policy \
  --name resorts-keyvault \
  --object-id <managed-identity-object-id> \
  --secret-permissions get list
```

### 2. Service Principal (CI/CD)
For automated deployments:

```bash
# Create service principal
az ad sp create-for-rbac \
  --name resorts-app-sp \
  --role contributor \
  --scopes /subscriptions/<subscription-id>/resourceGroups/resorts-rg

# Set environment variables
export AZURE_CLIENT_ID="<app-id>"
export AZURE_TENANT_ID="<tenant-id>"
export AZURE_CLIENT_SECRET="<password>"
export AZURE_KEYVAULT_URI="https://resorts-keyvault.vault.azure.net/"

# Grant Key Vault access
az keyvault set-policy \
  --name resorts-keyvault \
  --spn $AZURE_CLIENT_ID \
  --secret-permissions get list
```

### 3. Azure CLI (Local Development)
For local development:

```bash
# Login to Azure CLI
az login

# Set Key Vault URI
export AZURE_KEYVAULT_URI="https://resorts-keyvault.vault.azure.net/"

# Run application
mvn spring-boot:run
```

## Environment Variables

### Required
- `AZURE_KEYVAULT_URI`: Azure Key Vault URI (e.g., `https://resorts-keyvault.vault.azure.net/`)

### Optional
- `AZURE_KEYVAULT_ENABLED`: Enable/disable Key Vault integration (default: `true`)
- `AZURE_CLIENT_ID`: Service principal client ID (for non-managed identity auth)
- `AZURE_TENANT_ID`: Azure tenant ID (for non-managed identity auth)
- `AZURE_CLIENT_SECRET`: Service principal secret (for non-managed identity auth)

### Fallback Values
If Key Vault is unavailable, the application uses these fallback values from `application.properties`:
- `DB_HOST`: `db-prod.resorts-internal.com`
- `DB_USERNAME`: `admin`
- `DB_PASSWORD`: (empty - must be provided)

## Deployment to Azure

### Azure App Service

```bash
# Create App Service
az webapp create \
  --name resorts-app \
  --resource-group resorts-rg \
  --plan resorts-plan \
  --runtime "JAVA:8-jre8"

# Enable Managed Identity
az webapp identity assign \
  --name resorts-app \
  --resource-group resorts-rg

# Configure environment variables
az webapp config appsettings set \
  --name resorts-app \
  --resource-group resorts-rg \
  --settings \
    AZURE_KEYVAULT_URI="https://resorts-keyvault.vault.azure.net/"

# Deploy application
az webapp deploy \
  --name resorts-app \
  --resource-group resorts-rg \
  --src-path target/resortsLite-1.0.0.jar \
  --type jar
```

### Azure Container Apps

```bash
# Create Container App
az containerapp create \
  --name resorts-app \
  --resource-group resorts-rg \
  --environment resorts-env \
  --image <your-container-registry>/resortslite:latest \
  --target-port 8080 \
  --ingress external \
  --env-vars \
    AZURE_KEYVAULT_URI="https://resorts-keyvault.vault.azure.net/" \
  --system-assigned

# Grant Key Vault access to Container App identity
az keyvault set-policy \
  --name resorts-keyvault \
  --object-id <container-app-identity-object-id> \
  --secret-permissions get list
```

## Security Benefits

1. **No Credentials in Source Code**: Eliminates risk of credential exposure in version control
2. **No Credentials in Container Images**: Secrets are retrieved at runtime, not baked into images
3. **Centralized Secret Management**: All secrets managed in Azure Key Vault
4. **Automatic Credential Rotation**: Update secrets in Key Vault without redeploying application
5. **Audit Logging**: Azure Key Vault logs all secret access for compliance
6. **Access Control**: Fine-grained access control using Azure RBAC and Key Vault policies

## Troubleshooting

### Application fails to start with Key Vault error
- Verify `AZURE_KEYVAULT_URI` is set correctly
- Check authentication (Managed Identity, Service Principal, or Azure CLI)
- Verify Key Vault access policies grant `get` and `list` permissions for secrets
- Check Key Vault firewall rules allow access from your network/Azure service

### Secrets not loading from Key Vault
- Check application logs for Key Vault connection errors
- Verify secret names in Key Vault match expected names (`db-username`, `db-password`, `db-host`)
- Ensure secrets exist in Key Vault: `az keyvault secret list --vault-name resorts-keyvault`

### Local development without Azure access
- Set `AZURE_KEYVAULT_ENABLED=false` to disable Key Vault integration
- Provide credentials via environment variables: `DB_USERNAME`, `DB_PASSWORD`, `DB_HOST`

## Testing

### Test Key Vault Connection

```bash
# Set environment variables
export AZURE_KEYVAULT_URI="https://resorts-keyvault.vault.azure.net/"

# Run application
mvn spring-boot:run

# Check logs for successful Key Vault connection
# Expected output: "Successfully loaded 3 secrets from Azure Key Vault"
```

### Test with Mock Secrets (Local Development)

```bash
# Disable Key Vault and use environment variables
export AZURE_KEYVAULT_ENABLED=false
export DB_USERNAME="testuser"
export DB_PASSWORD="testpass"
export DB_HOST="localhost"

mvn spring-boot:run
```

## Migration Checklist

- [x] Remove hard-coded credentials from `BookingService.java`
- [x] Add Azure Key Vault dependencies to `pom.xml`
- [x] Create `AzureKeyVaultConfig` configuration class
- [x] Update `application.properties` with Key Vault configuration
- [ ] Create Azure Key Vault in Azure portal or CLI
- [ ] Add database credentials as secrets in Key Vault
- [ ] Configure Managed Identity for Azure service
- [ ] Grant Key Vault access to Managed Identity
- [ ] Set `AZURE_KEYVAULT_URI` environment variable
- [ ] Test application startup and database connectivity
- [ ] Verify secrets are loaded from Key Vault (check logs)
- [ ] Remove any remaining hard-coded credentials from configuration files

## References

- [Azure Key Vault Documentation](https://docs.microsoft.com/azure/key-vault/)
- [Azure SDK for Java - Key Vault Secrets](https://docs.microsoft.com/java/api/overview/azure/security-keyvault-secrets-readme)
- [DefaultAzureCredential](https://docs.microsoft.com/java/api/com.azure.identity.defaultazurecredential)
- [Managed Identity Overview](https://docs.microsoft.com/azure/active-directory/managed-identities-azure-resources/overview)
