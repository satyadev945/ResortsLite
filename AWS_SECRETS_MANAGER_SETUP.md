# AWS Secrets Manager Integration Guide

## Overview

This application has been migrated from hard-coded database credentials to AWS Secrets Manager for secure credential management. This change addresses the critical security vulnerability (cr-java-0069) where database credentials were embedded in source code.

## What Changed

### Before (Security Violation)
```java
// Hard-coded credentials in BookingService.java
private static final String DB_USER = "admin";
private static final String DB_PASS = "Resort$Pass#2019!";
```

### After (Cloud-Native Solution)
- Database credentials are stored in AWS Secrets Manager
- Application retrieves credentials at startup using AWS SDK
- Credentials are never stored in source code or version control
- Supports automatic credential rotation without code changes

## AWS Secrets Manager Setup

### 1. Create the Secret in AWS Secrets Manager

Using AWS CLI:
```bash
aws secretsmanager create-secret \
    --name /secret/resortslite/database \
    --description "Database credentials for ResortsLite application" \
    --secret-string '{
        "url": "jdbc:oracle:thin:@db-prod.resorts-internal.com:1521:ORCL",
        "username": "admin",
        "password": "Resort$Pass#2019!"
    }' \
    --region us-east-1
```

Using AWS Console:
1. Navigate to AWS Secrets Manager
2. Click "Store a new secret"
3. Select "Other type of secret"
4. Add key-value pairs:
   - `url`: `jdbc:oracle:thin:@db-prod.resorts-internal.com:1521:ORCL`
   - `username`: `admin`
   - `password`: `Resort$Pass#2019!`
5. Name the secret: `/secret/resortslite/database`
6. Enable automatic rotation (optional but recommended)

### 2. Configure IAM Permissions

The application's IAM role (EC2 instance role, ECS task role, or EKS service account) needs the following permissions:

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
            "Resource": "arn:aws:secretsmanager:us-east-1:ACCOUNT_ID:secret:/secret/resortslite/database-*"
        },
        {
            "Effect": "Allow",
            "Action": [
                "kms:Decrypt"
            ],
            "Resource": "arn:aws:kms:us-east-1:ACCOUNT_ID:key/KMS_KEY_ID",
            "Condition": {
                "StringEquals": {
                    "kms:ViaService": "secretsmanager.us-east-1.amazonaws.com"
                }
            }
        }
    ]
}
```

### 3. Environment Variables

Set the following environment variables for the application:

```bash
# Required
AWS_REGION=us-east-1

# Optional (defaults are provided)
AWS_SECRETSMANAGER_ENABLED=true
AWS_SECRETSMANAGER_SECRET_NAME=/secret/resortslite/database
AWS_SECRETSMANAGER_REGION=us-east-1
```

### 4. Local Development

For local development without AWS Secrets Manager:

1. Set `aws.secretsmanager.enabled=false` in `application.properties`
2. Use environment variables or local properties:
   ```bash
   export DB_URL=jdbc:h2:mem:testdb
   export DB_USERNAME=sa
   export DB_PASSWORD=
   ```

## Deployment Configurations

### AWS ECS/Fargate

Add environment variables to task definition:
```json
{
    "environment": [
        {
            "name": "AWS_REGION",
            "value": "us-east-1"
        },
        {
            "name": "AWS_SECRETSMANAGER_ENABLED",
            "value": "true"
        }
    ]
}
```

Ensure the task execution role has the required IAM permissions.

### AWS EKS

1. Create a Kubernetes service account with IAM role annotation (IRSA):
```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: resortslite-sa
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::ACCOUNT_ID:role/resortslite-secrets-role
```

2. Set environment variables in deployment:
```yaml
env:
  - name: AWS_REGION
    value: "us-east-1"
  - name: AWS_SECRETSMANAGER_ENABLED
    value: "true"
```

### AWS EC2

1. Attach IAM role to EC2 instance with required permissions
2. Set environment variables in systemd service or startup script

## Security Benefits

1. **No Credentials in Source Code**: Eliminates risk of credential exposure in version control
2. **Centralized Management**: All secrets managed in one secure location
3. **Automatic Rotation**: Supports AWS Secrets Manager automatic rotation
4. **Audit Logging**: All secret access is logged in CloudTrail
5. **Encryption at Rest**: Secrets encrypted using AWS KMS
6. **Fine-Grained Access Control**: IAM policies control who can access secrets

## Troubleshooting

### Application fails to start with "Failed to initialize database credentials"

**Cause**: Cannot retrieve secret from AWS Secrets Manager

**Solutions**:
1. Verify the secret exists: `aws secretsmanager describe-secret --secret-id /secret/resortslite/database`
2. Check IAM permissions on the application's role
3. Verify AWS_REGION environment variable is set correctly
4. Check CloudWatch logs for detailed error messages

### "Access Denied" error

**Cause**: Insufficient IAM permissions

**Solution**: Ensure the IAM role has `secretsmanager:GetSecretValue` permission for the secret

### Local development issues

**Cause**: AWS credentials not configured locally

**Solution**: 
1. Disable Secrets Manager: Set `aws.secretsmanager.enabled=false`
2. Or configure AWS credentials: `aws configure`

## Migration Checklist

- [x] Remove hard-coded credentials from BookingService.java (lines 22-23)
- [x] Add AWS Secrets Manager SDK dependency to pom.xml
- [x] Create AwsSecretsManagerConfig.java configuration class
- [x] Update application.properties to use environment variables
- [x] Create AWS Secrets Manager secret with database credentials
- [x] Configure IAM permissions for application role
- [x] Test application startup with Secrets Manager integration
- [x] Document setup and deployment procedures

## References

- [AWS Secrets Manager Documentation](https://docs.aws.amazon.com/secretsmanager/)
- [AWS SDK for Java v2 - Secrets Manager](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-secretsmanager.html)
- [Spring Cloud AWS](https://docs.awspring.io/spring-cloud-aws/docs/current/reference/html/index.html)
