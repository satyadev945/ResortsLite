# AWS Systems Manager Parameter Store Setup

## Overview

This application has been updated to use AWS Systems Manager Parameter Store for externalized configuration of environment-specific URLs. This enables environment-agnostic deployments and follows cloud-native best practices.

## Fixed Issues

**Rule ID**: cr-java-0071 - Hard-coded Environment URLs  
**Severity**: CRITICAL  
**Category**: configuration-management

### Files Modified:
1. `BookingController.java` - Line 66: Inventory service URL externalized
2. `ReportService.java` - Line 66: Reports download URL externalized
3. `pom.xml` - Added AWS Systems Manager SDK dependency
4. `AwsParameterStoreConfig.java` - New configuration class for Parameter Store integration

## Required Parameter Store Parameters

Before deploying this application to AWS, create the following parameters in AWS Systems Manager Parameter Store:

### 1. Inventory Service URL
```bash
aws ssm put-parameter \
    --name "/resortslite/inventory/service/url" \
    --value "https://inventory-service.prod.internal:8081/rooms/available" \
    --type String \
    --description "Inventory service endpoint URL" \
    --region us-east-1
```

### 2. Reports Download URL
```bash
aws ssm put-parameter \
    --name "/resortslite/reports/download/url" \
    --value "https://reports.resorts-internal.com:8080/download" \
    --type String \
    --description "Reports download base URL" \
    --region us-east-1
```

### 3. Payment Endpoint (Optional - for future use)
```bash
aws ssm put-parameter \
    --name "/resortslite/payment/endpoint" \
    --value "https://payment-svc.internal:9090/charge" \
    --type String \
    --description "Payment service endpoint URL" \
    --region us-east-1
```

### 4. Notification Endpoint (Optional - for future use)
```bash
aws ssm put-parameter \
    --name "/resortslite/notification/endpoint" \
    --value "https://notify.internal:7070/send" \
    --type String \
    --description "Notification service endpoint URL" \
    --region us-east-1
```

## IAM Permissions Required

The application's IAM role (EC2 instance role, ECS task role, or Lambda execution role) must have the following permissions:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "ssm:GetParameter",
                "ssm:GetParameters"
            ],
            "Resource": [
                "arn:aws:ssm:us-east-1:*:parameter/resortslite/*"
            ]
        }
    ]
}
```

## Environment-Specific Configuration

### Development Environment
```bash
aws ssm put-parameter --name "/resortslite/inventory/service/url" \
    --value "https://inventory-service.dev.internal:8081/rooms/available" \
    --type String --region us-east-1

aws ssm put-parameter --name "/resortslite/reports/download/url" \
    --value "https://reports.dev.resorts-internal.com:8080/download" \
    --type String --region us-east-1
```

### Staging Environment
```bash
aws ssm put-parameter --name "/resortslite/inventory/service/url" \
    --value "https://inventory-service.staging.internal:8081/rooms/available" \
    --type String --region us-east-1

aws ssm put-parameter --name "/resortslite/reports/download/url" \
    --value "https://reports.staging.resorts-internal.com:8080/download" \
    --type String --region us-east-1
```

### Production Environment
```bash
aws ssm put-parameter --name "/resortslite/inventory/service/url" \
    --value "https://inventory-service.prod.internal:8081/rooms/available" \
    --type String --region us-east-1

aws ssm put-parameter --name "/resortslite/reports/download/url" \
    --value "https://reports.prod.resorts-internal.com:8080/download" \
    --type String --region us-east-1
```

## Local Development

For local development without AWS Parameter Store access, the application uses default values defined in `AwsParameterStoreConfig.java`:

- **Inventory Service URL**: `https://inventory-service.internal:8081/rooms/available`
- **Reports Download URL**: `https://reports.resorts-internal.com:8080/download`

These defaults allow developers to run the application locally without AWS credentials.

## Updating Parameters

To update a parameter value without restarting the application:

```bash
# Update the parameter in AWS
aws ssm put-parameter \
    --name "/resortslite/inventory/service/url" \
    --value "https://new-inventory-service.internal:8081/rooms/available" \
    --type String \
    --overwrite \
    --region us-east-1

# The application will use the new value on the next request
# For immediate refresh, restart the application or implement a refresh endpoint
```

## Verification

After deployment, verify that parameters are loaded correctly:

1. Check application logs for Parameter Store loading messages:
   ```
   INFO: Loaded parameter from AWS Parameter Store: /resortslite/inventory/service/url
   INFO: Loaded parameter from AWS Parameter Store: /resortslite/reports/download/url
   ```

2. Test the endpoints:
   ```bash
   curl https://your-app-url/api/bookings/availability?roomType=SUITE
   ```

3. Verify the response contains the correct inventory endpoint URL.

## Troubleshooting

### Issue: "Failed to load parameter from Parameter Store"

**Cause**: IAM role lacks SSM permissions or parameter doesn't exist.

**Solution**:
1. Verify IAM role has `ssm:GetParameter` permission
2. Verify parameter exists: `aws ssm get-parameter --name "/resortslite/inventory/service/url"`
3. Check AWS region matches application configuration

### Issue: Application uses default values instead of Parameter Store values

**Cause**: AWS credentials not configured or region mismatch.

**Solution**:
1. Verify `AWS_REGION` environment variable is set
2. Verify IAM role is attached to EC2/ECS/Lambda
3. Check CloudWatch logs for SSM errors

## Benefits

✅ **Environment Portability**: Same application code runs in dev, staging, and production  
✅ **Security**: URLs can be updated without code changes or redeployment  
✅ **Compliance**: Follows AWS Well-Architected Framework best practices  
✅ **Auditability**: Parameter changes are logged in CloudTrail  
✅ **Flexibility**: Parameters can be updated independently of application deployments

## Next Steps

1. Create Parameter Store parameters for your target environment
2. Configure IAM role with SSM permissions
3. Deploy the application
4. Verify parameters are loaded correctly
5. Update parameters as needed for different environments
