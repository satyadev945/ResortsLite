# Azure Active Directory Authentication Setup

## Overview

This application has been migrated from file-based authentication to Azure Active Directory (Entra ID) authentication using Spring Security and Microsoft Authentication Library (MSAL).

**Fixed Issue**: cr-java-0090 - File-based Authentication

## What Changed

### Before (File-based Authentication)
- Used MD5 hash for generating confirmation codes
- No centralized identity management
- Local authentication state
- Not horizontally scalable
- Security vulnerabilities with weak hashing

### After (Azure AD Authentication)
- Centralized identity management via Azure Active Directory
- JWT bearer token authentication
- Stateless authentication (horizontally scalable)
- Integration with Azure security features (MFA, conditional access)
- Secure user identity from Azure AD claims

## Architecture

### Components Added

1. **AzureAdSecurityConfig.java**
   - Spring Security configuration for Azure AD
   - JWT bearer token validation
   - OAuth2 resource server setup
   - Stateless session management

2. **AzureAdAuthenticationService.java**
   - Helper service for Azure AD authentication
   - Extract user information from JWT tokens
   - Get authenticated user's identity and claims

3. **Updated BookingService.java**
   - Removed MD5 hash-based confirmation code generation
   - Uses Azure AD authenticated user context
   - Generates secure confirmation codes based on user's Azure AD identity

4. **Updated BookingController.java**
   - Added `@PreAuthorize("isAuthenticated()")` annotations
   - Includes authenticated user information in responses
   - Demonstrates Azure AD authentication usage

## Configuration

### Required Environment Variables

Set these environment variables in your Azure Container Apps / App Service:

```bash
# Azure Active Directory Configuration
AZURE_AD_ENABLED=true
AZURE_AD_TENANT_ID=<your-tenant-id>
AZURE_AD_CLIENT_ID=<your-application-client-id>
AZURE_AD_CLIENT_SECRET=<your-client-secret>
AZURE_AD_APP_ID_URI=api://resortslite
```

### Azure AD Application Setup

1. **Register Application in Azure AD**
   - Go to Azure Portal → Azure Active Directory → App registrations
   - Click "New registration"
   - Name: "ResortsLite API"
   - Supported account types: "Accounts in this organizational directory only"
   - Click "Register"

2. **Configure API Permissions**
   - Go to "API permissions"
   - Add permission → Microsoft Graph → Delegated permissions
   - Select: `User.Read`
   - Grant admin consent

3. **Create Client Secret**
   - Go to "Certificates & secrets"
   - Click "New client secret"
   - Description: "ResortsLite API Secret"
   - Expires: 24 months (or as per your policy)
   - Copy the secret value (you won't see it again)

4. **Expose API**
   - Go to "Expose an API"
   - Set Application ID URI: `api://resortslite`
   - Add a scope: `Booking.Create` (for booking creation)
   - Add a scope: `Booking.Read` (for reading bookings)

5. **Configure Authentication**
   - Go to "Authentication"
   - Add platform → Web
   - Redirect URIs: `https://your-app.azurewebsites.net/login/oauth2/code/azure`
   - Implicit grant: Enable "ID tokens"

### application.properties Configuration

The following configuration is already added to `application.properties`:

```properties
# Azure Active Directory (Entra ID) Configuration
spring.cloud.azure.active-directory.enabled=${AZURE_AD_ENABLED:true}
spring.cloud.azure.active-directory.profile.tenant-id=${AZURE_AD_TENANT_ID:}
spring.cloud.azure.active-directory.credential.client-id=${AZURE_AD_CLIENT_ID:}
spring.cloud.azure.active-directory.credential.client-secret=${AZURE_AD_CLIENT_SECRET:}
spring.cloud.azure.active-directory.app-id-uri=${AZURE_AD_APP_ID_URI:api://resortslite}
spring.cloud.azure.active-directory.authorization-clients.graph.scopes=https://graph.microsoft.com/User.Read
```

## Authentication Flow

### 1. Client Authentication
```bash
# Client obtains JWT token from Azure AD
POST https://login.microsoftonline.com/{tenant-id}/oauth2/v2.0/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
&client_id={client-id}
&client_secret={client-secret}
&scope=api://resortslite/.default
```

### 2. API Request with Token
```bash
# Client includes token in Authorization header
POST https://your-app.azurewebsites.net/api/bookings/create
Authorization: Bearer {jwt-token}
Content-Type: application/json

{
  "guestName": "John Doe",
  "roomType": "DELUXE",
  "checkIn": "2024-01-15",
  "checkOut": "2024-01-20"
}
```

### 3. Token Validation
- Spring Security validates JWT token signature against Azure AD
- Extracts user claims (oid, email, name, roles)
- Populates SecurityContext with authenticated user
- Allows access to protected endpoints

## Benefits

### Security
- ✅ Centralized identity management
- ✅ Multi-Factor Authentication (MFA) support
- ✅ Conditional access policies
- ✅ Token-based authentication (no passwords in requests)
- ✅ Automatic token expiration and refresh

### Scalability
- ✅ Stateless authentication (no server-side sessions)
- ✅ Horizontal scaling without shared state
- ✅ Works seamlessly with Azure Container Apps / AKS
- ✅ No session affinity required

### Integration
- ✅ Single Sign-On (SSO) across applications
- ✅ Integration with Azure Key Vault for secrets
- ✅ Azure AD audit logs for compliance
- ✅ Role-based access control (RBAC) with Azure AD groups

### Developer Experience
- ✅ Standard OAuth2 / OpenID Connect protocols
- ✅ Spring Security integration
- ✅ Easy to test with Azure CLI authentication
- ✅ Comprehensive documentation and tooling

## Testing

### Local Development

1. **Install Azure CLI**
   ```bash
   curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash
   ```

2. **Login to Azure**
   ```bash
   az login
   az account set --subscription <your-subscription-id>
   ```

3. **Set Environment Variables**
   ```bash
   export AZURE_AD_TENANT_ID=<your-tenant-id>
   export AZURE_AD_CLIENT_ID=<your-client-id>
   export AZURE_AD_CLIENT_SECRET=<your-client-secret>
   ```

4. **Run Application**
   ```bash
   mvn spring-boot:run
   ```

### Testing with Postman

1. **Get Access Token**
   - Create new request: POST `https://login.microsoftonline.com/{tenant-id}/oauth2/v2.0/token`
   - Body (x-www-form-urlencoded):
     - `grant_type`: `client_credentials`
     - `client_id`: `{your-client-id}`
     - `client_secret`: `{your-client-secret}`
     - `scope`: `api://resortslite/.default`
   - Send request and copy the `access_token` from response

2. **Call Protected API**
   - Create new request: POST `http://localhost:8080/api/bookings/create`
   - Headers:
     - `Authorization`: `Bearer {access-token}`
     - `Content-Type`: `application/json`
   - Body (JSON):
     ```json
     {
       "guestName": "John Doe",
       "roomType": "DELUXE",
       "checkIn": "2024-01-15",
       "checkOut": "2024-01-20"
     }
     ```
   - Send request

## Troubleshooting

### Common Issues

1. **401 Unauthorized**
   - Check if token is included in Authorization header
   - Verify token is not expired
   - Ensure client ID and tenant ID are correct

2. **403 Forbidden**
   - Check if user has required permissions
   - Verify API permissions are granted in Azure AD
   - Ensure admin consent is granted

3. **Token Validation Failed**
   - Verify tenant ID matches the token issuer
   - Check if application ID URI is correct
   - Ensure client secret is valid and not expired

4. **Connection Timeout**
   - Check network connectivity to Azure AD endpoints
   - Verify firewall rules allow outbound HTTPS traffic
   - Ensure DNS resolution works for login.microsoftonline.com

## Migration Checklist

- [x] Added Azure AD Spring Security dependencies to pom.xml
- [x] Created AzureAdSecurityConfig.java for security configuration
- [x] Created AzureAdAuthenticationService.java for authentication utilities
- [x] Updated BookingService.java to use Azure AD authentication
- [x] Updated BookingController.java with authentication annotations
- [x] Added Azure AD configuration to application.properties
- [x] Removed MD5 hash-based authentication code
- [x] Documented Azure AD setup and configuration

## References

- [Azure Active Directory Documentation](https://docs.microsoft.com/en-us/azure/active-directory/)
- [Spring Cloud Azure Active Directory](https://docs.microsoft.com/en-us/azure/developer/java/spring-framework/spring-boot-starter-for-azure-active-directory-developer-guide)
- [Microsoft Authentication Library (MSAL)](https://docs.microsoft.com/en-us/azure/active-directory/develop/msal-overview)
- [OAuth 2.0 and OpenID Connect](https://docs.microsoft.com/en-us/azure/active-directory/develop/active-directory-v2-protocols)
