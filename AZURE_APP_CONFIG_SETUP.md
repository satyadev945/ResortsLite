# Azure App Configuration Setup Guide

## Overview
This application has been updated to use Azure App Configuration for externalized configuration management, enabling environment-agnostic deployments and eliminating hard-coded environment URLs.

## Fixed Issues (cr-java-0071)
The following hard-coded environment URLs have been externalized:

1. **BookingController.java (Line 66)**
   - **Before**: `String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";`
   - **After**: Uses `${app.inventory.endpoint}` from Azure App Configuration
   - **Configuration Key**: `app.inventory.endpoint`

2. **ReportService.java (Line 66)**
   - **Before**: `return "https://reports.resorts-internal.com:" + serverPort + "/download/" + reportName;`
   - **After**: Uses `${app.reports.download.baseurl}` from Azure App Configuration
   - **Configuration Key**: `app.reports.download.baseurl`

## Azure App Configuration Setup

### Step 1: Create Azure App Configuration Store

```bash
# Create resource group (if not exists)
az group create --name resorts-rg --location eastus

# Create App Configuration store
az appconfig create \
  --name resorts-appconfig \
  --resource-group resorts-rg \
  --location eastus \
  --sku Standard
```

### Step 2: Add Configuration Keys

Add the following configuration keys to your Azure App Configuration store for each environment:

#### Development Environment (label: dev)
```bash
az appconfig kv set \
  --name resorts-appconfig \
  --key app.inventory.endpoint \
  --value "http://inventory-svc-dev.internal:8081/rooms" \
  --label dev

az appconfig kv set \
  --name resorts-appconfig \
  --key app.reports.download.baseurl \
  --value "https://reports-dev.resorts-internal.com" \
  --label dev

az appconfig kv set \
  --name resorts-appconfig \
  --key app.payment.endpoint \
  --value "http://payment-svc-dev.internal:9090/charge" \
  --label dev
```

#### Staging Environment (label: staging)
```bash
az appconfig kv set \
  --name resorts-appconfig \
  --key app.inventory.endpoint \
  --value "http://inventory-svc-staging.internal:8081/rooms" \
  --label staging

az appconfig kv set \
  --name resorts-appconfig \
  --key app.reports.download.baseurl \
  --value "https://reports-staging.resorts-internal.com" \
  --label staging

az appconfig kv set \
  --name resorts-appconfig \
  --key app.payment.endpoint \
  --value "http://payment-svc-staging.internal:9090/charge" \
  --label staging
```

#### Production Environment (label: prod)
```bash
az appconfig kv set \
  --name resorts-appconfig \
  --key app.inventory.endpoint \
  --value "https://inventory-svc.resorts.com/rooms" \
  --label prod

az appconfig kv set \
  --name resorts-appconfig \
  --key app.reports.download.baseurl \
  --value "https://reports.resorts.com" \
  --label prod

az appconfig kv set \
  --name resorts-appconfig \
  --key app.payment.endpoint \
  --value "https://payment-svc.resorts.com/charge" \
  --label prod
```

### Step 3: Configure Application Authentication

#### Option 1: Using Connection String (Development)
```bash
# Get connection string
az appconfig credential list \
  --name resorts-appconfig \
  --resource-group resorts-rg

# Set environment variable
export AZURE_APP_CONFIG_CONNECTION_STRING="Endpoint=https://resorts-appconfig.azconfig.io;Id=xxx;Secret=xxx"
export AZURE_APP_CONFIG_ENABLED=true
export AZURE_APP_CONFIG_LABEL=dev
```

#### Option 2: Using Managed Identity (Production - Recommended)
```bash
# Enable system-assigned managed identity for your Azure App Service
az webapp identity assign \
  --name resorts-app \
  --resource-group resorts-rg

# Grant the managed identity access to App Configuration
PRINCIPAL_ID=$(az webapp identity show \
  --name resorts-app \
  --resource-group resorts-rg \
  --query principalId -o tsv)

az role assignment create \
  --role "App Configuration Data Reader" \
  --assignee $PRINCIPAL_ID \
  --scope /subscriptions/{subscription-id}/resourceGroups/resorts-rg/providers/Microsoft.AppConfiguration/configurationStores/resorts-appconfig

# Set environment variables in App Service
az webapp config appsettings set \
  --name resorts-app \
  --resource-group resorts-rg \
  --settings \
    AZURE_APP_CONFIG_ENDPOINT="https://resorts-appconfig.azconfig.io" \
    AZURE_APP_CONFIG_ENABLED="true" \
    AZURE_APP_CONFIG_LABEL="prod"
```

## Environment Variables

The following environment variables control Azure App Configuration:

| Variable | Description | Default | Required |
|----------|-------------|---------|----------|
| `AZURE_APP_CONFIG_ENABLED` | Enable/disable Azure App Configuration | `false` | No |
| `AZURE_APP_CONFIG_ENDPOINT` | App Configuration endpoint URL | `https://resorts-appconfig.azconfig.io` | Yes (if enabled) |
| `AZURE_APP_CONFIG_CONNECTION_STRING` | Connection string for authentication | - | Yes (if not using Managed Identity) |
| `AZURE_APP_CONFIG_LABEL` | Environment label (dev/staging/prod) | `prod` | No |

## Configuration Keys Reference

| Key | Description | Example Value |
|-----|-------------|---------------|
| `app.inventory.endpoint` | Inventory service base URL | `https://inventory-svc.resorts.com/rooms` |
| `app.reports.download.baseurl` | Report download service base URL | `https://reports.resorts.com` |
| `app.payment.endpoint` | Payment service endpoint URL | `https://payment-svc.resorts.com/charge` |

## Local Development

For local development, you can use the default values in `application.properties` or override them with environment variables:

```bash
# Using environment variables
export APP_INVENTORY_ENDPOINT="http://localhost:8081/rooms"
export APP_REPORTS_DOWNLOAD_BASEURL="http://localhost:8080"
export APP_PAYMENT_ENDPOINT="http://localhost:9090/charge"

# Run the application
mvn spring-boot:run
```

## Testing Configuration

To verify the configuration is loaded correctly:

```bash
# Check application logs for Azure App Configuration initialization
# You should see logs indicating successful connection to App Configuration

# Test the endpoints
curl http://localhost:8080/api/bookings/availability?roomType=SUITE
```

## Troubleshooting

### Issue: Configuration not loading from Azure App Configuration

**Solution**: 
1. Verify `AZURE_APP_CONFIG_ENABLED=true`
2. Check connection string or managed identity permissions
3. Verify the label matches your environment
4. Check application logs for authentication errors

### Issue: Using default values instead of Azure App Configuration

**Solution**:
1. Ensure Azure App Configuration dependency is in pom.xml
2. Verify environment variables are set correctly
3. Check that keys exist in Azure App Configuration with the correct label

### Issue: Authentication failures

**Solution**:
1. For connection string: Verify the connection string is valid and not expired
2. For managed identity: Verify the identity has "App Configuration Data Reader" role
3. Check network connectivity to Azure App Configuration endpoint

## Benefits

1. **Environment Agnostic**: No code changes needed for different environments
2. **Centralized Configuration**: All environment-specific URLs managed in one place
3. **Dynamic Updates**: Configuration can be updated without redeploying the application
4. **Security**: No hard-coded URLs in source code
5. **Compliance**: Follows cloud-native 12-factor app principles

## Migration Checklist

- [x] Added Azure App Configuration dependency to pom.xml
- [x] Externalized hard-coded URLs in BookingController.java
- [x] Externalized hard-coded URLs in ReportService.java
- [x] Updated application.properties with Azure App Configuration settings
- [x] Added environment variable support for all configuration keys
- [ ] Create Azure App Configuration store
- [ ] Add configuration keys for all environments
- [ ] Configure authentication (connection string or managed identity)
- [ ] Test in development environment
- [ ] Deploy to staging and verify configuration
- [ ] Deploy to production

## References

- [Azure App Configuration Documentation](https://docs.microsoft.com/azure/azure-app-configuration/)
- [Spring Cloud Azure App Configuration](https://docs.microsoft.com/azure/developer/java/spring-framework/spring-cloud-azure)
- [12-Factor App Configuration](https://12factor.net/config)
