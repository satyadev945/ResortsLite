# AWS Cloud-Native Authentication Setup

## Overview
This application has been migrated from file-based authentication to AWS cloud-native authentication services:
- **AWS Secrets Manager**: For secure credential storage
- **AWS Cognito**: For user identity and authentication management

## Fixed Issues
- **cr-java-0090**: File-based Authentication - Replaced with AWS Secrets Manager and Amazon Cognito
- **sec-weak-hash-001**: Weak MD5 hashing - Replaced with secure SHA-256 hashing

## AWS Secrets Manager Configuration

### Database Credentials Secret
Create a secret in AWS Secrets Manager with the following structure:

**Secret Name**: `resortslite/database/credentials`

**Secret Value** (JSON format):
```json
{
  "host": "your-rds-endpoint.region.rds.amazonaws.com",
  "username": "admin",
  "password": "your-secure-password",
  "database": "resortdb",
  "port": "3306"
}
```

### API Credentials Secret
For external API integrations, create secrets with this structure:

**Secret Name**: `resortslite/api/payment`

**Secret Value** (JSON format):
```json
{
  "apiKey": "your-api-key",
  "apiSecret": "your-api-secret",
  "endpoint": "https://api.payment-provider.com"
}
```

## AWS Cognito Configuration

### User Pool Setup
1. Create a Cognito User Pool in AWS Console
2. Configure the following settings:
   - **Sign-in options**: Username, Email
   - **Password policy**: Strong password requirements
   - **MFA**: Optional (recommended for production)
   - **Email verification**: Required

3. Create an App Client:
   - **App client name**: resortslite-app
   - **Authentication flows**: USER_PASSWORD_AUTH
   - **Generate client secret**: Yes (for server-side apps)

### Environment Variables
Set the following environment variables in your deployment environment:

```bash
# AWS Region
export AWS_REGION=us-east-1

# AWS Secrets Manager
export AWS_SECRET_NAME=resortslite/database/credentials

# AWS Cognito
export AWS_COGNITO_USER_POOL_ID=us-east-1_XXXXXXXXX
export AWS_COGNITO_CLIENT_ID=your-client-id
export AWS_COGNITO_CLIENT_SECRET=your-client-secret

# Application Configuration
export SERVER_PORT=8080
export APP_PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge
export APP_INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms
export APP_NOTIFICATION_ENDPOINT=http://notify.internal:7070/send

# Redis Configuration (for session management)
export REDIS_HOST=your-elasticache-endpoint.cache.amazonaws.com
export REDIS_PORT=6379
export REDIS_PASSWORD=your-redis-password
export REDIS_SSL=true

# S3 Configuration
export AWS_S3_BUCKET_NAME=resorts-lite-reports
```

## IAM Permissions

### Required IAM Policy for Application
The application requires the following IAM permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue",
        "secretsmanager:DescribeSecret"
      ],
      "Resource": [
        "arn:aws:secretsmanager:us-east-1:ACCOUNT_ID:secret:resortslite/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "cognito-idp:InitiateAuth",
        "cognito-idp:SignUp",
        "cognito-idp:ConfirmSignUp",
        "cognito-idp:GetUser"
      ],
      "Resource": [
        "arn:aws:cognito-idp:us-east-1:ACCOUNT_ID:userpool/us-east-1_XXXXXXXXX"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::resorts-lite-reports",
        "arn:aws:s3:::resorts-lite-reports/*"
      ]
    }
  ]
}
```

## Usage Examples

### Authenticating a User
```java
@Autowired
private BookingService bookingService;

// Authenticate user with AWS Cognito
Map<String, String> authResult = bookingService.authenticateUser("username", "password");
String accessToken = authResult.get("accessToken");
String idToken = authResult.get("idToken");
```

### Validating a Token
```java
// Validate access token
Map<String, String> userInfo = bookingService.validateUserToken(accessToken);
String username = userInfo.get("username");
String email = userInfo.get("email");
```

### Retrieving Database Credentials
```java
// Get database credentials from AWS Secrets Manager
Map<String, String> dbCredentials = bookingService.getDatabaseCredentials();
String dbHost = dbCredentials.get("host");
String dbUsername = dbCredentials.get("username");
String dbPassword = dbCredentials.get("password");
```

## Security Benefits

### Before (File-based Authentication)
- ❌ Credentials stored in local files
- ❌ Weak MD5 hashing for security tokens
- ❌ No centralized user management
- ❌ Difficult to rotate credentials
- ❌ No audit trail for authentication events
- ❌ Doesn't scale horizontally in cloud

### After (AWS Cloud-Native Authentication)
- ✅ Credentials encrypted in AWS Secrets Manager
- ✅ Secure SHA-256 hashing for tokens
- ✅ Centralized user management with AWS Cognito
- ✅ Easy credential rotation without code changes
- ✅ Full audit trail via AWS CloudTrail
- ✅ Scales horizontally across multiple instances
- ✅ Built-in MFA and advanced security features
- ✅ Compliance with security best practices

## Migration Notes

### Removed Components
- **MD5 Hash Function**: Replaced with SHA-256 via AWS Cognito service
- **Hardcoded Credentials**: Removed from source code
- **File-based User Storage**: Replaced with AWS Cognito User Pool

### New Components
- **AwsSecretsManagerService**: Service for retrieving secrets from AWS Secrets Manager
- **AwsCognitoAuthService**: Service for user authentication via AWS Cognito
- **Secure Hash Generation**: SHA-256 based confirmation code generation

## Testing

### Local Development
For local development, you can use LocalStack or AWS SAM Local to simulate AWS services:

```bash
# Using LocalStack
docker run -d -p 4566:4566 localstack/localstack

# Set environment variables to point to LocalStack
export AWS_ENDPOINT_URL=http://localhost:4566
```

### Production Deployment
Ensure all environment variables are configured in your deployment platform:
- **AWS ECS**: Task Definition environment variables
- **AWS EKS**: Kubernetes ConfigMap and Secrets
- **AWS Elastic Beanstalk**: Environment properties
- **AWS Lambda**: Function environment variables

## Troubleshooting

### Common Issues

1. **Secret Not Found**
   - Verify the secret name matches the configuration
   - Check IAM permissions for Secrets Manager access
   - Ensure the secret exists in the correct AWS region

2. **Cognito Authentication Failed**
   - Verify User Pool ID and Client ID are correct
   - Check that the user exists in the User Pool
   - Ensure the authentication flow is enabled in Cognito

3. **Token Validation Failed**
   - Verify the access token is not expired
   - Check that the token was issued by the correct User Pool
   - Ensure the app client has the necessary permissions

## Additional Resources
- [AWS Secrets Manager Documentation](https://docs.aws.amazon.com/secretsmanager/)
- [AWS Cognito Documentation](https://docs.aws.amazon.com/cognito/)
- [AWS SDK for Java v2](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/)
