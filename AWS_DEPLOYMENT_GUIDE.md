# AWS Deployment Configuration Guide

## Overview
This guide explains how to configure the ResortsLite application for AWS cloud deployment after fixing hard-coded port issues (cr-java-0077).

## Fixed Hard-Coded Port Issues

### Changes Made
1. **ReportService.java (Line 46)**: Removed hard-coded port default value from `@Value("${server.port:8080}")` to `@Value("${server.port}")`
2. **application.properties**: 
   - Converted `server.port=8080` to `server.port=${SERVER_PORT:8080}`
   - Converted service endpoints to use environment variables
   - Removed hard-coded port from report download URL

## Required Environment Variables

### Core Application Configuration

#### SERVER_PORT
- **Purpose**: Application server port for dynamic assignment by ECS/EKS
- **Default**: 8080 (fallback only)
- **AWS Parameter Store Path**: `/resortslite/config/server-port`
- **Example**: `8080`, `8443`, `3000`

```bash
# Local Development
export SERVER_PORT=8080

# AWS ECS Task Definition
{
  "name": "SERVER_PORT",
  "valueFrom": "arn:aws:ssm:us-east-1:123456789012:parameter/resortslite/config/server-port"
}
```

### Service Endpoint Configuration

#### APP_PAYMENT_ENDPOINT
- **Purpose**: Payment service endpoint with dynamic port
- **Default**: `http://payment-svc.internal:9090/charge`
- **AWS Parameter Store Path**: `/resortslite/services/payment-endpoint`
- **Recommended**: Use AWS Cloud Map service discovery or ALB DNS

```bash
# Using AWS Cloud Map
export APP_PAYMENT_ENDPOINT=http://payment-svc.resortslite.local:9090/charge

# Using Application Load Balancer
export APP_PAYMENT_ENDPOINT=https://payment-api.resortslite.com/charge
```

#### APP_INVENTORY_ENDPOINT
- **Purpose**: Inventory service endpoint with dynamic port
- **Default**: `http://inventory-svc.internal:8081/rooms`
- **AWS Parameter Store Path**: `/resortslite/services/inventory-endpoint`

```bash
export APP_INVENTORY_ENDPOINT=http://inventory-svc.resortslite.local:8081/rooms
```

#### APP_NOTIFICATION_ENDPOINT
- **Purpose**: Notification service endpoint with dynamic port
- **Default**: `http://notify.internal:7070/send`
- **AWS Parameter Store Path**: `/resortslite/services/notification-endpoint`

```bash
export APP_NOTIFICATION_ENDPOINT=http://notify-svc.resortslite.local:7070/send
```

#### APP_REPORTS_DOWNLOAD_BASEURL
- **Purpose**: Base URL for report downloads (no hard-coded port)
- **Default**: `https://reports.resorts-internal.com`
- **AWS Parameter Store Path**: `/resortslite/config/reports-baseurl`
- **Note**: Port should be handled by load balancer, not hard-coded

```bash
# Using CloudFront + ALB
export APP_REPORTS_DOWNLOAD_BASEURL=https://reports.resortslite.com

# Using API Gateway
export APP_REPORTS_DOWNLOAD_BASEURL=https://api.resortslite.com/reports
```

## AWS Systems Manager Parameter Store Setup

### Create Parameters

```bash
# Server Port
aws ssm put-parameter \
  --name "/resortslite/config/server-port" \
  --value "8080" \
  --type "String" \
  --description "Application server port for ECS/EKS dynamic assignment"

# Payment Service Endpoint
aws ssm put-parameter \
  --name "/resortslite/services/payment-endpoint" \
  --value "http://payment-svc.resortslite.local:9090/charge" \
  --type "String" \
  --description "Payment service endpoint with Cloud Map service discovery"

# Inventory Service Endpoint
aws ssm put-parameter \
  --name "/resortslite/services/inventory-endpoint" \
  --value "http://inventory-svc.resortslite.local:8081/rooms" \
  --type "String" \
  --description "Inventory service endpoint with Cloud Map service discovery"

# Notification Service Endpoint
aws ssm put-parameter \
  --name "/resortslite/services/notification-endpoint" \
  --value "http://notify-svc.resortslite.local:7070/send" \
  --type "String" \
  --description "Notification service endpoint with Cloud Map service discovery"

# Reports Download Base URL
aws ssm put-parameter \
  --name "/resortslite/config/reports-baseurl" \
  --value "https://reports.resortslite.com" \
  --type "String" \
  --description "Base URL for report downloads via CloudFront/ALB"
```

## AWS ECS Configuration

### Task Definition Example

```json
{
  "family": "resortslite-app",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "containerDefinitions": [
    {
      "name": "resortslite",
      "image": "123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest",
      "portMappings": [
        {
          "containerPort": 8080,
          "protocol": "tcp"
        }
      ],
      "environment": [],
      "secrets": [
        {
          "name": "SERVER_PORT",
          "valueFrom": "arn:aws:ssm:us-east-1:123456789012:parameter/resortslite/config/server-port"
        },
        {
          "name": "APP_PAYMENT_ENDPOINT",
          "valueFrom": "arn:aws:ssm:us-east-1:123456789012:parameter/resortslite/services/payment-endpoint"
        },
        {
          "name": "APP_INVENTORY_ENDPOINT",
          "valueFrom": "arn:aws:ssm:us-east-1:123456789012:parameter/resortslite/services/inventory-endpoint"
        },
        {
          "name": "APP_NOTIFICATION_ENDPOINT",
          "valueFrom": "arn:aws:ssm:us-east-1:123456789012:parameter/resortslite/services/notification-endpoint"
        },
        {
          "name": "APP_REPORTS_DOWNLOAD_BASEURL",
          "valueFrom": "arn:aws:ssm:us-east-1:123456789012:parameter/resortslite/config/reports-baseurl"
        },
        {
          "name": "AWS_SECRET_NAME",
          "valueFrom": "arn:aws:ssm:us-east-1:123456789012:parameter/resortslite/config/secret-name"
        },
        {
          "name": "AWS_S3_BUCKET_NAME",
          "valueFrom": "arn:aws:ssm:us-east-1:123456789012:parameter/resortslite/config/s3-bucket"
        }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/resortslite",
          "awslogs-region": "us-east-1",
          "awslogs-stream-prefix": "ecs"
        }
      }
    }
  ],
  "executionRoleArn": "arn:aws:iam::123456789012:role/ecsTaskExecutionRole",
  "taskRoleArn": "arn:aws:iam::123456789012:role/resortsliteTaskRole"
}
```

## AWS EKS Configuration

### ConfigMap Example

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: resortslite-config
  namespace: resortslite
data:
  SERVER_PORT: "8080"
  AWS_REGION: "us-east-1"
  AWS_S3_REPORTS_PREFIX: "reports/"
  AWS_S3_BACKUPS_PREFIX: "backups/nightly/"
```

### Deployment with External Secrets Operator

```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: resortslite-params
  namespace: resortslite
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: aws-parameter-store
    kind: SecretStore
  target:
    name: resortslite-secrets
    creationPolicy: Owner
  data:
    - secretKey: SERVER_PORT
      remoteRef:
        key: /resortslite/config/server-port
    - secretKey: APP_PAYMENT_ENDPOINT
      remoteRef:
        key: /resortslite/services/payment-endpoint
    - secretKey: APP_INVENTORY_ENDPOINT
      remoteRef:
        key: /resortslite/services/inventory-endpoint
    - secretKey: APP_NOTIFICATION_ENDPOINT
      remoteRef:
        key: /resortslite/services/notification-endpoint
    - secretKey: APP_REPORTS_DOWNLOAD_BASEURL
      remoteRef:
        key: /resortslite/config/reports-baseurl
```

### Deployment Manifest

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: resortslite
  namespace: resortslite
spec:
  replicas: 3
  selector:
    matchLabels:
      app: resortslite
  template:
    metadata:
      labels:
        app: resortslite
    spec:
      containers:
      - name: resortslite
        image: 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
        ports:
        - containerPort: 8080
          name: http
        envFrom:
        - configMapRef:
            name: resortslite-config
        - secretRef:
            name: resortslite-secrets
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 20
          periodSeconds: 5
```

## AWS Elastic Beanstalk Configuration

### .ebextensions/environment.config

```yaml
option_settings:
  aws:elasticbeanstalk:application:environment:
    SERVER_PORT: '`{"Ref": "ServerPort"}`'
    APP_PAYMENT_ENDPOINT: '`{"Ref": "PaymentEndpoint"}`'
    APP_INVENTORY_ENDPOINT: '`{"Ref": "InventoryEndpoint"}`'
    APP_NOTIFICATION_ENDPOINT: '`{"Ref": "NotificationEndpoint"}`'
    APP_REPORTS_DOWNLOAD_BASEURL: '`{"Ref": "ReportsBaseUrl"}`'
    AWS_REGION: '`{"Ref": "AWS::Region"}`'

Parameters:
  ServerPort:
    Type: String
    Default: "8080"
    Description: "Application server port"
  
  PaymentEndpoint:
    Type: String
    Description: "Payment service endpoint"
  
  InventoryEndpoint:
    Type: String
    Description: "Inventory service endpoint"
  
  NotificationEndpoint:
    Type: String
    Description: "Notification service endpoint"
  
  ReportsBaseUrl:
    Type: String
    Description: "Reports download base URL"
```

## AWS Service Discovery Integration

### Using AWS Cloud Map

```bash
# Create namespace
aws servicediscovery create-private-dns-namespace \
  --name resortslite.local \
  --vpc vpc-12345678 \
  --description "Service discovery namespace for ResortsLite"

# Create service
aws servicediscovery create-service \
  --name payment-svc \
  --namespace-id ns-12345678 \
  --dns-config "NamespaceId=ns-12345678,DnsRecords=[{Type=A,TTL=60}]" \
  --health-check-custom-config FailureThreshold=1
```

### ECS Service with Service Discovery

```json
{
  "serviceName": "resortslite",
  "taskDefinition": "resortslite-app:1",
  "desiredCount": 3,
  "launchType": "FARGATE",
  "networkConfiguration": {
    "awsvpcConfiguration": {
      "subnets": ["subnet-12345678", "subnet-87654321"],
      "securityGroups": ["sg-12345678"],
      "assignPublicIp": "DISABLED"
    }
  },
  "serviceRegistries": [
    {
      "registryArn": "arn:aws:servicediscovery:us-east-1:123456789012:service/srv-12345678"
    }
  ]
}
```

## IAM Permissions Required

### Task Execution Role (for ECS)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ssm:GetParameters",
        "ssm:GetParameter",
        "ssm:GetParametersByPath"
      ],
      "Resource": [
        "arn:aws:ssm:us-east-1:123456789012:parameter/resortslite/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue"
      ],
      "Resource": [
        "arn:aws:secretsmanager:us-east-1:123456789012:secret:resortslite/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "kms:Decrypt"
      ],
      "Resource": [
        "arn:aws:kms:us-east-1:123456789012:key/12345678-1234-1234-1234-123456789012"
      ]
    }
  ]
}
```

## Testing Configuration

### Local Testing with Environment Variables

```bash
# Set all required environment variables
export SERVER_PORT=8080
export APP_PAYMENT_ENDPOINT=http://localhost:9090/charge
export APP_INVENTORY_ENDPOINT=http://localhost:8081/rooms
export APP_NOTIFICATION_ENDPOINT=http://localhost:7070/send
export APP_REPORTS_DOWNLOAD_BASEURL=https://localhost:8443
export AWS_REGION=us-east-1
export AWS_S3_BUCKET_NAME=resorts-lite-reports-dev
export AWS_SECRET_NAME=resortslite/database/credentials

# Run application
mvn spring-boot:run
```

### Verify Configuration

```bash
# Check application startup logs
curl http://localhost:8080/actuator/env | jq '.propertySources[] | select(.name | contains("systemEnvironment"))'

# Verify port binding
netstat -an | grep 8080

# Test service endpoints
curl http://localhost:8080/api/bookings/system-info
```

## Troubleshooting

### Port Already in Use
```bash
# Find process using port
lsof -i :8080

# Kill process
kill -9 <PID>

# Or use different port
export SERVER_PORT=8081
```

### Parameter Store Access Denied
```bash
# Verify IAM permissions
aws ssm get-parameter --name /resortslite/config/server-port

# Check task role
aws iam get-role --role-name resortsliteTaskRole
```

### Service Discovery Not Working
```bash
# Verify namespace
aws servicediscovery list-namespaces

# Check service registration
aws servicediscovery list-services --filters Name=NAMESPACE_ID,Values=ns-12345678

# Test DNS resolution
nslookup payment-svc.resortslite.local
```

## Best Practices

1. **Never hard-code ports** - Always use environment variables or Parameter Store
2. **Use AWS Cloud Map** for service discovery instead of hard-coded hostnames
3. **Implement health checks** on dynamic ports for load balancer integration
4. **Use Application Load Balancer** to abstract port numbers from clients
5. **Enable container insights** for monitoring port usage and conflicts
6. **Use AWS Secrets Manager** for sensitive configuration (credentials, API keys)
7. **Implement graceful shutdown** to handle port release properly
8. **Use AWS App Mesh** for advanced service mesh capabilities with dynamic routing

## Related Documentation

- [AWS Systems Manager Parameter Store](https://docs.aws.amazon.com/systems-manager/latest/userguide/systems-manager-parameter-store.html)
- [AWS Cloud Map Service Discovery](https://docs.aws.amazon.com/cloud-map/latest/dg/what-is-cloud-map.html)
- [ECS Task Definitions](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definitions.html)
- [EKS External Secrets Operator](https://external-secrets.io/latest/)
- [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config)
