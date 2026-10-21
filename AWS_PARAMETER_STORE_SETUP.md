# AWS Systems Manager Parameter Store Configuration Guide

## Overview

This application has been updated to use AWS Systems Manager Parameter Store for externalizing environment-specific configuration values, replacing hard-coded URLs and endpoints.

**Fixed Issue**: cr-java-0071 - Hard-coded Environment URLs

## What Was Changed

### 1. Code Changes

#### BookingController.java
- **Before**: Hard-coded inventory service URL: `"http://inventory-service.internal:8081/rooms/available"`
- **After**: Externalized to `@Value("${app.inventory.service.url}")` retrieved from Parameter Store

#### ReportService.java
- **Before**: Hard-coded reports download URL: `"http://reports.resorts-internal.com:8080/download/"`
- **After**: Externalized to `@Value("${app.reports.download.base.url}")` retrieved from Parameter Store

### 2. New Configuration Class

Created `AwsParameterStoreConfig.java` to provide AWS Systems Manager Parameter Store integration.

### 3. Updated Dependencies

Added AWS SDK for Systems Manager (SSM) to `pom.xml`:
```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>ssm</artifactId>
    <version>${aws.sdk.version}</version>
</dependency>
```

## AWS Parameter Store Setup

### Required Parameters

Create the following parameters in AWS Systems Manager Parameter Store:

| Parameter Name | Type | Description | Example Value |
|---------------|------|-------------|---------------|
| `/config/resortslite/app.inventory.service.url` | String | Inventory service base URL | `https://inventory-service.prod.internal:8081` |
| `/config/resortslite/app.reports.download.base.url` | String | Reports download base URL | `https://reports.resorts-prod.com` |
| `/config/resortslite/app.payment.endpoint` | String | Payment service endpoint | `https://payment-svc.prod.internal:9090/charge` |
| `/config/resortslite/app.notification.endpoint` | String | Notification service endpoint | `https://notify.prod.internal:7070/send` |

### AWS CLI Commands

#### Create Parameters

```bash
# Inventory Service URL
aws ssm put-parameter \
  --name "/config/resortslite/app.inventory.service.url" \
  --value "https://inventory-service.prod.internal:8081" \
  --type "String" \
  --description "Inventory service base URL for production" \
  --tags "Key=Environment,Value=production" "Key=Application,Value=ResortsLite"

# Reports Download Base URL
aws ssm put-parameter \
  --name "/config/resortslite/app.reports.download.base.url" \
  --value "https://reports.resorts-prod.com" \
  --type "String" \
  --description "Reports download base URL for production" \
  --tags "Key=Environment,Value=production" "Key=Application,Value=ResortsLite"

# Payment Service Endpoint
aws ssm put-parameter \
  --name "/config/resortslite/app.payment.endpoint" \
  --value "https://payment-svc.prod.internal:9090/charge" \
  --type "String" \
  --description "Payment service endpoint for production" \
  --tags "Key=Environment,Value=production" "Key=Application,Value=ResortsLite"

# Notification Service Endpoint
aws ssm put-parameter \
  --name "/config/resortslite/app.notification.endpoint" \
  --value "https://notify.prod.internal:7070/send" \
  --type "String" \
  --description "Notification service endpoint for production" \
  --tags "Key=Environment,Value=production" "Key=Application,Value=ResortsLite"
```

#### Update Parameters

```bash
aws ssm put-parameter \
  --name "/config/resortslite/app.inventory.service.url" \
  --value "https://inventory-service.prod.internal:8081" \
  --type "String" \
  --overwrite
```

#### Retrieve Parameters

```bash
# Get a single parameter
aws ssm get-parameter \
  --name "/config/resortslite/app.inventory.service.url"

# Get multiple parameters
aws ssm get-parameters \
  --names "/config/resortslite/app.inventory.service.url" \
         "/config/resortslite/app.reports.download.base.url"

# Get all parameters by path
aws ssm get-parameters-by-path \
  --path "/config/resortslite/" \
  --recursive
```

### Environment-Specific Configuration

Use different parameter paths for different environments:

#### Development
```
/config/resortslite/dev/app.inventory.service.url
/config/resortslite/dev/app.reports.download.base.url
```

#### Staging
```
/config/resortslite/staging/app.inventory.service.url
/config/resortslite/staging/app.reports.download.base.url
```

#### Production
```
/config/resortslite/prod/app.inventory.service.url
/config/resortslite/prod/app.reports.download.base.url
```

Update `application.properties` to use environment-specific paths:
```properties
app.inventory.service.url=${APP_INVENTORY_SERVICE_URL:/config/resortslite/${ENVIRONMENT:prod}/app.inventory.service.url}
```

## IAM Permissions

### Required IAM Policy

The application's IAM role (EC2 instance role, ECS task role, or EKS service account) must have the following permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ssm:GetParameter",
        "ssm:GetParameters",
        "ssm:GetParametersByPath"
      ],
      "Resource": [
        "arn:aws:ssm:us-east-1:123456789012:parameter/config/resortslite/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "kms:Decrypt"
      ],
      "Resource": [
        "arn:aws:kms:us-east-1:123456789012:key/your-kms-key-id"
      ],
      "Condition": {
        "StringEquals": {
          "kms:ViaService": "ssm.us-east-1.amazonaws.com"
        }
      }
    }
  ]
}
```

### Attach Policy to IAM Role

```bash
# Create the policy
aws iam create-policy \
  --policy-name ResortsLiteParameterStoreAccess \
  --policy-document file://parameter-store-policy.json

# Attach to EC2 instance role
aws iam attach-role-policy \
  --role-name ResortsLiteEC2Role \
  --policy-arn arn:aws:iam::123456789012:policy/ResortsLiteParameterStoreAccess

# Attach to ECS task role
aws iam attach-role-policy \
  --role-name ResortsLiteECSTaskRole \
  --policy-arn arn:aws:iam::123456789012:policy/ResortsLiteParameterStoreAccess
```

## Application Configuration

### application.properties

The following properties control Parameter Store integration:

```properties
# Enable/disable Parameter Store integration
aws.parameterstore.enabled=${AWS_PARAMETER_STORE_ENABLED:true}

# AWS region for Parameter Store
aws.parameterstore.region=${AWS_REGION:us-east-1}

# Application configuration with fallback to environment variables
app.inventory.service.url=${APP_INVENTORY_SERVICE_URL:http://inventory-service.internal:8081}
app.reports.download.base.url=${APP_REPORTS_DOWNLOAD_BASE_URL:http://reports.resorts-internal.com:8080}
```

### Environment Variables

You can override Parameter Store values using environment variables:

```bash
export AWS_PARAMETER_STORE_ENABLED=true
export AWS_REGION=us-east-1
export APP_INVENTORY_SERVICE_URL=https://inventory-service.prod.internal:8081
export APP_REPORTS_DOWNLOAD_BASE_URL=https://reports.resorts-prod.com
```

## Deployment Considerations

### 1. Local Development

For local development, Parameter Store integration can be disabled:

```properties
aws.parameterstore.enabled=false
```

Or use default values in `application.properties`:

```properties
app.inventory.service.url=${APP_INVENTORY_SERVICE_URL:http://localhost:8081}
app.reports.download.base.url=${APP_REPORTS_DOWNLOAD_BASE_URL:http://localhost:8080}
```

### 2. AWS EC2 Deployment

- Attach IAM role with Parameter Store permissions to EC2 instance
- Application will automatically use instance credentials
- No need to configure AWS credentials explicitly

### 3. AWS ECS/Fargate Deployment

- Attach IAM role with Parameter Store permissions to ECS task
- Set environment variables in task definition:
  ```json
  {
    "environment": [
      {
        "name": "AWS_REGION",
        "value": "us-east-1"
      },
      {
        "name": "AWS_PARAMETER_STORE_ENABLED",
        "value": "true"
      }
    ]
  }
  ```

### 4. AWS EKS Deployment

- Use IAM Roles for Service Accounts (IRSA)
- Annotate Kubernetes service account with IAM role ARN
- Application will automatically use service account credentials

## Benefits

### 1. Environment-Agnostic Deployments
- Same application code works across all environments
- No code changes required for environment-specific values
- Simplified CI/CD pipelines

### 2. Centralized Configuration Management
- All configuration in one place (Parameter Store)
- Easy to update without redeploying application
- Version control and audit trail for configuration changes

### 3. Security
- Secure storage of sensitive configuration
- Integration with AWS KMS for encryption
- Fine-grained access control via IAM policies

### 4. Cloud-Native Best Practices
- Follows 12-factor app principles (externalized configuration)
- Compatible with AWS Well-Architected Framework
- Supports multi-environment deployments

## Troubleshooting

### Issue: Application cannot retrieve parameters

**Symptoms**: Application logs show errors like "Error retrieving parameter"

**Solutions**:
1. Verify IAM permissions are correctly configured
2. Check parameter names match exactly (case-sensitive)
3. Verify AWS region is correct
4. Ensure parameters exist in Parameter Store

### Issue: Application uses default values instead of Parameter Store values

**Symptoms**: Application uses fallback values from `application.properties`

**Solutions**:
1. Verify `aws.parameterstore.enabled=true`
2. Check AWS credentials are available
3. Verify parameter names in application.properties match Parameter Store
4. Check application logs for Parameter Store initialization messages

### Issue: Access denied errors

**Symptoms**: AWS SDK throws access denied exceptions

**Solutions**:
1. Verify IAM role has `ssm:GetParameter` permission
2. Check resource ARN in IAM policy matches parameter path
3. If using encrypted parameters, verify `kms:Decrypt` permission
4. Ensure IAM role is attached to EC2 instance/ECS task/EKS service account

## Testing

### Verify Parameter Store Integration

```bash
# Check if parameters exist
aws ssm get-parameters-by-path \
  --path "/config/resortslite/" \
  --recursive

# Test application with Parameter Store
curl http://localhost:8080/api/bookings/availability?roomType=SUITE

# Check application logs for Parameter Store initialization
tail -f logs/application.log | grep "Parameter Store"
```

## Migration Checklist

- [ ] Create all required parameters in AWS Systems Manager Parameter Store
- [ ] Configure IAM permissions for application's execution role
- [ ] Update application.properties with Parameter Store configuration
- [ ] Test application in development environment
- [ ] Verify parameters are retrieved correctly from Parameter Store
- [ ] Deploy to staging environment and test
- [ ] Deploy to production environment
- [ ] Monitor application logs for any Parameter Store errors
- [ ] Document environment-specific parameter values

## References

- [AWS Systems Manager Parameter Store Documentation](https://docs.aws.amazon.com/systems-manager/latest/userguide/systems-manager-parameter-store.html)
- [AWS SDK for Java v2 - SSM Client](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/ssm/SsmClient.html)
- [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config)
- [12-Factor App - Config](https://12factor.net/config)
