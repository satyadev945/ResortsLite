# AWS Secrets Manager Setup Guide

## Overview
This application has been migrated from hard-coded database credentials to AWS Secrets Manager for secure, cloud-native credential management.

## What Changed
- **Removed**: Hard-coded database credentials (`DB_USER`, `DB_PASS`, `DB_HOST`) from `BookingService.java`
- **Added**: AWS Secrets Manager integration via `SecretsManagerConfig.java`
- **Added**: AWS SDK Secrets Manager dependency in `pom.xml`

## AWS Secrets Manager Configuration

### 1. Create Secret in AWS Secrets Manager

Create a secret in AWS Secrets Manager with the following JSON structure:

```json
{
  "host": "db-prod.resorts-internal.com",
  "username": "admin",
  "password": "YourSecurePassword123!"
}
```

**Using AWS CLI:**
```bash
aws secretsmanager create-secret \
    --name resortslite/database/credentials \
    --description "Database credentials for ResortsLite application" \
    --secret-string '{"host":"db-prod.resorts-internal.com","username":"admin","password":"YourSecurePassword123!"}' \
    --region us-east-1
```

**Using AWS Console:**
1. Navigate to AWS Secrets Manager
2. Click "Store a new secret"
3. Select "Other type of secret"
4. Add key-value pairs: `host`, `username`, `password`
5. Name the secret: `resortslite/database/credentials`
6. Complete the wizard

### 2. IAM Permissions

The application's IAM role (ECS Task Role, EC2 Instance Role, or Lambda Execution Role) must have permission to read the secret:

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
      "Resource": "arn:aws:secretsmanager:us-east-1:ACCOUNT_ID:secret:resortslite/database/credentials-*"
    }
  ]
}
```

### 3. Environment Variables

Set the following environment variables when deploying the application:

| Variable | Description | Default Value |
|----------|-------------|---------------|
| `AWS_SECRET_NAME` | Name of the secret in AWS Secrets Manager | `resortslite/database/credentials` |
| `AWS_REGION` | AWS region where the secret is stored | `us-east-1` |

**For ECS Task Definition:**
```json
{
  "environment": [
    {
      "name": "AWS_SECRET_NAME",
      "value": "resortslite/database/credentials"
    },
    {
      "name": "AWS_REGION",
      "value": "us-east-1"
    }
  ]
}
```

**For Kubernetes Deployment:**
```yaml
env:
  - name: AWS_SECRET_NAME
    value: "resortslite/database/credentials"
  - name: AWS_REGION
    value: "us-east-1"
```

## Local Development

For local development without AWS Secrets Manager access, the application falls back to environment variables:

```bash
export DB_HOST=localhost
export DB_USER=sa
export DB_PASS=
export AWS_REGION=us-east-1

# Run the application
mvn spring-boot:run
```

## Secret Rotation

AWS Secrets Manager supports automatic secret rotation. To enable:

1. Navigate to your secret in AWS Secrets Manager
2. Click "Edit rotation"
3. Enable automatic rotation
4. Configure rotation schedule (e.g., every 30 days)
5. Select or create a Lambda function for rotation

The application automatically refreshes secrets on startup. For runtime rotation without restart, call the `refreshSecrets()` method on `SecretsManagerConfig`.

## Troubleshooting

### Error: "Failed to load secrets from AWS Secrets Manager"
- **Cause**: IAM permissions missing or secret not found
- **Solution**: Verify IAM role has `secretsmanager:GetSecretValue` permission and secret exists

### Error: "Access Denied"
- **Cause**: IAM role lacks permission to access the secret
- **Solution**: Add the IAM policy shown in section 2 above

### Application uses wrong credentials
- **Cause**: Secret name mismatch or wrong AWS region
- **Solution**: Verify `AWS_SECRET_NAME` and `AWS_REGION` environment variables

## Benefits of This Migration

✅ **Security**: Credentials no longer stored in source code or version control  
✅ **Rotation**: Supports automatic credential rotation without code changes  
✅ **Compliance**: Meets cloud security compliance requirements  
✅ **Audit**: All secret access is logged in AWS CloudTrail  
✅ **Encryption**: Secrets encrypted at rest using AWS KMS  

## References

- [AWS Secrets Manager Documentation](https://docs.aws.amazon.com/secretsmanager/)
- [AWS SDK for Java - Secrets Manager](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-secretsmanager.html)
- [Best Practices for Secrets Management](https://docs.aws.amazon.com/secretsmanager/latest/userguide/best-practices.html)
