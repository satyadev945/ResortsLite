# AWS Systems Manager Parameter Store Configuration Guide

## Overview
This document describes how to configure the ResortsLite application to use AWS Systems Manager Parameter Store for externalized configuration, addressing cloud readiness issue **cr-java-0071** (Hard-coded Environment URLs).

## Fixed Issues

### 1. Hard-coded Inventory Service URL (BookingController.java)
**Before:**
```java
String inventoryUrl = "http://inventory-service.internal:8081/rooms/available";
```

**After:**
```java
@Value("${app.inventory.endpoint}")
private String inventoryServiceUrl;
// ...
String inventoryUrl = inventoryServiceUrl + "/available";
```

### 2. Hard-coded Reports Download URL (ReportService.java)
**Before:**
```java
return "https://reports.resorts-internal.com:" + serverPort + "/download/" + reportName;
```

**After:**
```java
@Value("${app.reports.download.baseurl}")
private String reportsDownloadBaseUrl;
// ...
return reportsDownloadBaseUrl + "/download/" + reportName;
```

## AWS Systems Manager Parameter Store Setup

### Step 1: Create Parameters in AWS Parameter Store

Use the AWS CLI or AWS Console to create the following parameters:

```bash
# Inventory Service Endpoint
aws ssm put-parameter \
  --name "/resortslite/app/inventory/endpoint" \
  --value "https://inventory-service.internal:8081/rooms" \
  --type "String" \
  --description "Inventory service endpoint URL" \
  --region us-east-1

# Reports Download Base URL
aws ssm put-parameter \
  --name "/resortslite/app/reports/download/baseurl" \
  --value "https://reports.resorts-internal.com:8080" \
  --type "String" \
  --description "Reports download base URL" \
  --region us-east-1

# Payment Service Endpoint (existing configuration)
aws ssm put-parameter \
  --name "/resortslite/app/payment/endpoint" \
  --value "https://payment-svc.internal:9090/charge" \
  --type "String" \
  --description "Payment service endpoint URL" \
  --region us-east-1

# Notification Service Endpoint (existing configuration)
aws ssm put-parameter \
  --name "/resortslite/app/notification/endpoint" \
  --value "https://notify.internal:7070/send" \
  --type "String" \
  --description "Notification service endpoint URL" \
  --region us-east-1
```

### Step 2: Configure IAM Permissions

Ensure your EC2 instance role, ECS task role, or EKS service account has the following IAM permissions:

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
        "arn:aws:ssm:us-east-1:*:parameter/resortslite/*"
      ]
    }
  ]
}
```

### Step 3: Configure Spring Boot to Use Parameter Store

#### Option A: Using Spring Cloud AWS (Recommended)

Add the following dependency to `pom.xml`:

```xml
<dependency>
    <groupId>io.awspring.cloud</groupId>
    <artifactId>spring-cloud-starter-aws-parameter-store-config</artifactId>
    <version>2.4.4</version>
</dependency>
```

Update `application.properties`:

```properties
# Enable AWS Parameter Store integration
aws.paramstore.enabled=true
aws.paramstore.prefix=/resortslite
aws.paramstore.profile-separator=_
aws.paramstore.fail-fast=true
```

#### Option B: Using Environment Variables (Current Implementation)

The application currently uses environment variables with fallback defaults. Set these environment variables in your deployment:

```bash
# For Docker/ECS
export APP_INVENTORY_ENDPOINT="https://inventory-service.internal:8081/rooms"
export APP_REPORTS_DOWNLOAD_BASEURL="https://reports.resorts-internal.com:8080"

# For Kubernetes
kubectl create configmap resortslite-config \
  --from-literal=APP_INVENTORY_ENDPOINT="https://inventory-service.internal:8081/rooms" \
  --from-literal=APP_REPORTS_DOWNLOAD_BASEURL="https://reports.resorts-internal.com:8080"
```

#### Option C: Custom Parameter Store Integration

Create a configuration class to load parameters at startup:

```java
@Configuration
public class ParameterStoreConfig {
    
    @Bean
    public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
        PropertySourcesPlaceholderConfigurer configurer = new PropertySourcesPlaceholderConfigurer();
        
        // Load parameters from AWS Systems Manager Parameter Store
        SsmClient ssmClient = SsmClient.builder()
            .region(Region.US_EAST_1)
            .build();
        
        GetParametersByPathRequest request = GetParametersByPathRequest.builder()
            .path("/resortslite/app")
            .recursive(true)
            .withDecryption(true)
            .build();
        
        GetParametersByPathResponse response = ssmClient.getParametersByPath(request);
        
        Properties properties = new Properties();
        for (Parameter parameter : response.parameters()) {
            String key = parameter.name().replace("/resortslite/app/", "app.")
                .replace("/", ".");
            properties.setProperty(key, parameter.value());
        }
        
        configurer.setProperties(properties);
        return configurer;
    }
}
```

## Environment-Specific Configuration

### Development Environment
```properties
app.inventory.endpoint=http://localhost:8081/rooms
app.reports.download.baseurl=http://localhost:8080
```

### Staging Environment
```properties
app.inventory.endpoint=https://inventory-staging.resorts-internal.com/rooms
app.reports.download.baseurl=https://reports-staging.resorts-internal.com
```

### Production Environment
```properties
app.inventory.endpoint=https://inventory-prod.resorts-internal.com/rooms
app.reports.download.baseurl=https://reports-prod.resorts-internal.com
```

## Testing the Configuration

### 1. Verify Parameter Store Access
```bash
aws ssm get-parameter --name "/resortslite/app/inventory/endpoint" --region us-east-1
```

### 2. Test Application Startup
```bash
# Set environment variables
export APP_INVENTORY_ENDPOINT="https://inventory-service.internal:8081/rooms"
export APP_REPORTS_DOWNLOAD_BASEURL="https://reports.resorts-internal.com:8080"

# Run the application
java -jar target/resortsLite-1.0.0.jar
```

### 3. Verify Configuration Loading
Check the application logs for:
```
INFO: Loaded configuration from AWS Systems Manager Parameter Store
INFO: app.inventory.endpoint = https://inventory-service.internal:8081/rooms
INFO: app.reports.download.baseurl = https://reports.resorts-internal.com:8080
```

## Benefits of This Approach

1. **Environment Portability**: No code changes needed when deploying to different environments
2. **Security**: Sensitive URLs are not hard-coded in source code
3. **Centralized Management**: All configuration managed in AWS Systems Manager
4. **Audit Trail**: Parameter Store provides change history and audit logs
5. **Dynamic Updates**: Configuration can be updated without redeploying the application
6. **Encryption**: Supports encryption at rest using AWS KMS
7. **IAM Integration**: Access control using AWS IAM policies

## Migration Checklist

- [x] Replace hard-coded inventory service URL with externalized configuration
- [x] Replace hard-coded reports download URL with externalized configuration
- [x] Update application.properties with environment variable placeholders
- [ ] Create parameters in AWS Systems Manager Parameter Store
- [ ] Configure IAM permissions for Parameter Store access
- [ ] Update deployment scripts to set environment variables
- [ ] Test configuration in development environment
- [ ] Deploy to staging environment and verify
- [ ] Deploy to production environment

## Additional Resources

- [AWS Systems Manager Parameter Store Documentation](https://docs.aws.amazon.com/systems-manager/latest/userguide/systems-manager-parameter-store.html)
- [Spring Cloud AWS Documentation](https://docs.awspring.io/spring-cloud-aws/docs/current/reference/html/index.html)
- [12-Factor App Configuration](https://12factor.net/config)
