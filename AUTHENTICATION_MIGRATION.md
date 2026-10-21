# AWS Cognito Authentication Migration

## Overview
This document describes the migration from file-based authentication to AWS Cognito and AWS Secrets Manager for the ResortsLite application.

## Issue Fixed: cr-java-0090 - File-based Authentication

### Problem
The application previously stored authentication credentials and used weak MD5 hashing for security tokens. This approach:
- Did not scale horizontally in distributed cloud environments
- Created security vulnerabilities with hardcoded credentials
- Used weak MD5 hashing algorithm (RFC 6151 deprecated)
- Lacked centralized user management and audit capabilities

### Solution
Migrated to AWS cloud-native authentication services:
1. **AWS Secrets Manager** - For secure credential storage
2. **Amazon Cognito** - For user identity and authentication management
3. **SHA-256 Hashing** - Replaced weak MD5 with secure SHA-256 algorithm

## Architecture Changes

### 1. AWS Cognito Integration
**File**: `src/main/java/com/demo/resortslite/config/AwsCognitoConfig.java`

Provides:
- Centralized user authentication and authorization
- Built-in user lifecycle management (registration, password reset, MFA)
- Encrypted credential storage with automatic rotation support
- Horizontal scalability for distributed cloud environments
- Audit logging through AWS CloudTrail

### 2. Authentication Service
**File**: `src/main/java/com/demo/resortslite/service/AuthenticationService.java`

Features:
- User authentication via AWS Cognito
- Secure token generation using SHA-256
- User management operations (get user details, validate tokens)
- Secure confirmation code generation (replaces MD5)

### 3. Updated BookingService
**File**: `src/main/java/com/demo/resortslite/BookingService.java`

Changes:
- Removed weak MD5 hashing method
- Integrated AuthenticationService for secure confirmation codes
- Uses SHA-256 for all security-related hashing operations

## Configuration

### Environment Variables Required

```bash
# AWS Cognito Configuration
AWS_COGNITO_ENABLED=true
AWS_COGNITO_USER_POOL_ID=us-east-1_AbCdEfGhI
AWS_COGNITO_CLIENT_ID=your-client-id-here
AWS_REGION=us-east-1

# AWS Secrets Manager Configuration
AWS_SECRETSMANAGER_ENABLED=true
AWS_SECRETSMANAGER_SECRET_NAME=/secret/resortslite/database
AWS_SECRETSMANAGER_REGION=us-east-1
```

### AWS Cognito User Pool Setup

1. **Create User Pool**:
   ```bash
   aws cognito-idp create-user-pool \
     --pool-name resortslite-users \
     --policies "PasswordPolicy={MinimumLength=8,RequireUppercase=true,RequireLowercase=true,RequireNumbers=true,RequireSymbols=true}" \
     --auto-verified-attributes email \
     --region us-east-1
   ```

2. **Create App Client**:
   ```bash
   aws cognito-idp create-user-pool-client \
     --user-pool-id us-east-1_AbCdEfGhI \
     --client-name resortslite-app \
     --explicit-auth-flows ADMIN_NO_SRP_AUTH \
     --region us-east-1
   ```

3. **Create Test User**:
   ```bash
   aws cognito-idp admin-create-user \
     --user-pool-id us-east-1_AbCdEfGhI \
     --username testuser@example.com \
     --user-attributes Name=email,Value=testuser@example.com \
     --temporary-password TempPass123! \
     --region us-east-1
   ```

### AWS Secrets Manager Setup

1. **Create Database Secret**:
   ```bash
   aws secretsmanager create-secret \
     --name /secret/resortslite/database \
     --description "ResortsLite database credentials" \
     --secret-string '{"url":"jdbc:oracle:thin:@db-prod.resorts-internal.com:1521:ORCL","username":"admin","password":"Resort$Pass#2019!"}' \
     --region us-east-1
   ```

## IAM Permissions Required

### For Application EC2/ECS Role

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "cognito-idp:AdminInitiateAuth",
        "cognito-idp:AdminGetUser",
        "cognito-idp:AdminCreateUser",
        "cognito-idp:AdminSetUserPassword",
        "cognito-idp:ListUsers"
      ],
      "Resource": "arn:aws:cognito-idp:us-east-1:ACCOUNT_ID:userpool/us-east-1_AbCdEfGhI"
    },
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue"
      ],
      "Resource": "arn:aws:secretsmanager:us-east-1:ACCOUNT_ID:secret:/secret/resortslite/database-*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "kms:Decrypt"
      ],
      "Resource": "arn:aws:kms:us-east-1:ACCOUNT_ID:key/KEY_ID",
      "Condition": {
        "StringEquals": {
          "kms:ViaService": [
            "secretsmanager.us-east-1.amazonaws.com"
          ]
        }
      }
    }
  ]
}
```

## Dependencies Added

### Maven Dependencies (pom.xml)

```xml
<!-- AWS Cognito for user authentication -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>cognitoidentityprovider</artifactId>
    <version>${aws.sdk.version}</version>
</dependency>

<!-- Spring Security -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

## Usage Examples

### Authenticate User

```java
@Autowired
private AuthenticationService authenticationService;

public void authenticateUser(String username, String password) {
    Map<String, Object> result = authenticationService.authenticateUser(username, password);
    
    if ((Boolean) result.get("success")) {
        String accessToken = (String) result.get("accessToken");
        String idToken = (String) result.get("idToken");
        // Use tokens for subsequent API calls
    } else {
        String message = (String) result.get("message");
        // Handle authentication failure
    }
}
```

### Generate Secure Confirmation Code

```java
@Autowired
private AuthenticationService authenticationService;

public String createBookingConfirmation(String bookingId, String guestName) {
    // Generates secure SHA-256 based confirmation code
    String confirmCode = authenticationService.generateSecureConfirmationCode(
        bookingId + guestName
    );
    return confirmCode;
}
```

## Security Benefits

1. **Centralized Authentication**: All user authentication is managed by AWS Cognito
2. **Encrypted Storage**: Credentials stored in AWS Secrets Manager with encryption at rest
3. **Automatic Rotation**: Supports automatic credential rotation
4. **Audit Logging**: All authentication events logged to AWS CloudTrail
5. **MFA Support**: Can enable multi-factor authentication via Cognito
6. **Strong Hashing**: SHA-256 replaces weak MD5 algorithm
7. **Horizontal Scalability**: Stateless authentication suitable for cloud scaling

## Migration Checklist

- [x] Add AWS Cognito dependencies to pom.xml
- [x] Create AwsCognitoConfig configuration class
- [x] Create AuthenticationService with Cognito integration
- [x] Update BookingService to use AuthenticationService
- [x] Remove MD5 hashing method
- [x] Add Cognito configuration to application.properties
- [x] Document IAM permissions required
- [ ] Create Cognito User Pool in AWS
- [ ] Create App Client in Cognito
- [ ] Configure environment variables in deployment
- [ ] Test authentication flow
- [ ] Migrate existing users to Cognito (if applicable)

## Testing

### Local Development
For local development without AWS Cognito:
```properties
aws.cognito.enabled=false
```

The application will use fallback authentication mechanisms.

### Production
Ensure all environment variables are set:
```bash
export AWS_COGNITO_ENABLED=true
export AWS_COGNITO_USER_POOL_ID=us-east-1_AbCdEfGhI
export AWS_COGNITO_CLIENT_ID=your-client-id
export AWS_REGION=us-east-1
```

## Troubleshooting

### Common Issues

1. **Authentication fails with "User not found"**
   - Verify user exists in Cognito User Pool
   - Check user pool ID is correct

2. **"Access Denied" errors**
   - Verify IAM role has required Cognito permissions
   - Check user pool ARN in IAM policy

3. **"Secret not found" errors**
   - Verify secret exists in AWS Secrets Manager
   - Check secret name matches configuration
   - Verify IAM role has secretsmanager:GetSecretValue permission

## References

- [AWS Cognito Documentation](https://docs.aws.amazon.com/cognito/)
- [AWS Secrets Manager Documentation](https://docs.aws.amazon.com/secretsmanager/)
- [Spring Security with Cognito](https://docs.spring.io/spring-security/reference/)
- [SHA-256 Hashing Best Practices](https://nvlpubs.nist.gov/nistpubs/FIPS/NIST.FIPS.180-4.pdf)
