# ResortsLite — AWS ECS Fargate Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Build and Push Docker Image](#build-and-push-docker-image)
6. [AWS ECS Fargate Prerequisites](#aws-ecs-fargate-prerequisites)
7. [ECS Task Definition Explained](#ecs-task-definition-explained)
8. [ECS Service Configuration](#ecs-service-configuration)
9. [ECS Fargate Deployment Walkthrough](#ecs-fargate-deployment-walkthrough)
10. [Environment Variables Reference](#environment-variables-reference)
11. [Secrets Management](#secrets-management)
12. [ECS-Specific Troubleshooting](#ecs-specific-troubleshooting)
13. [Scaling and Management](#scaling-and-management)
14. [Security Considerations](#security-considerations)
15. [Java-Specific Notes](#java-specific-notes)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.x  
**Java Version**: 8  
**Build Tool**: Maven  
**Package Type**: JAR (executable)  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`  
**Target Platform**: AWS ECS Fargate  

ResortsLite is a legacy resort booking REST API modernised for cloud-native deployment on AWS ECS Fargate. It uses JWT-based stateless authentication, Amazon ElastiCache for Memcached (distributed caching), and EFS-backed file storage for reports.

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker Desktop | 24.x+ | Build and run containers locally |
| Docker Compose | 2.x+ | Multi-container local orchestration |
| Java JDK | 8+ | Local development (optional) |
| Maven | 3.9.x | Local builds (optional) |

### AWS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | Interact with AWS services |
| Python 3 | 3.8+ | Used by deploy scripts for JSON manipulation |
| Docker | 24.x+ | Build and push images |

---

## Project Structure

```
dar/
├── Dockerfile                  # Multi-stage Docker build
├── docker-compose.yml          # Local development compose file
├── .dockerignore               # Files excluded from Docker build context
├── pom.xml                     # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   └── ReportService.java
│       └── resources/
│           └── application.properties
├── ecs/
│   ├── task-definition.json    # ECS Fargate task definition
│   └── service-definition.json # ECS service definition
├── scripts/
│   ├── build-push.sh           # Linux/macOS build & push script
│   ├── build-push.bat          # Windows build & push script
│   ├── deploy-image.sh         # Linux/macOS ECS deploy script
│   └── deploy-image.bat        # Windows ECS deploy script
└── docs/
    └── DEPLOYMENT.md           # This file
```

---

## Local Development with Docker Compose

### 1. Configure Environment Variables

Create a `.env` file in the project root (never commit this file):

```bash
# Database
SPRING_DATASOURCE_URL=jdbc:h2:mem:testdb
SPRING_DATASOURCE_USERNAME=sa
SPRING_DATASOURCE_PASSWORD=
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.h2.Driver
SPRING_JPA_DATABASE_PLATFORM=org.hibernate.dialect.H2Dialect

# Secrets
JWT_SIGNING_SECRET=local-dev-secret-change-in-prod

# Memcached (use localhost if running locally without ElastiCache)
MEMCACHED_ENDPOINT=localhost:11211

# File paths
REPORT_BASE_PATH=/tmp/reports/
BACKUP_PATH=/tmp/backups/

# Internal services
PAYMENT_API_URL=http://payment-service:9090/payments/charge
```

### 2. Start the Application

```bash
# From the project root (dar/)
docker compose up --build
```

### 3. Verify the Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Create a booking
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=SUITE"
```

### 4. Stop the Application

```bash
docker compose down
```

---

## Build and Push Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

### Windows

```cmd
scripts\build-push.bat
```

The script will prompt you to:
1. Enter an image tag (default: `latest`)
2. Select a registry: **AWS ECR** or **Docker Hub**
3. Provide registry-specific credentials

**AWS ECR flow**: The script automatically retrieves your Account ID, creates the ECR repository if it doesn't exist, authenticates, builds, and pushes.

**Docker Hub flow**: Provide your username, password/token, and repository name.

> **Note**: The Docker build context is always the project root (`dar/`). The Dockerfile uses a multi-stage build — Maven builder stage followed by a lightweight `eclipse-temurin:8-jdk-alpine` runtime stage.

---

## AWS ECS Fargate Prerequisites

### 1. AWS CLI Configuration

```bash
aws configure
# Enter: AWS Access Key ID, Secret Access Key, Default region, Output format
```

### 2. VPC and Networking

Ensure you have:
- A **VPC** with DNS resolution enabled
- At least **2 public or private subnets** in different Availability Zones
- A **Security Group** that allows:
  - Inbound TCP on port **8080** (from ALB or your IP)
  - Outbound TCP on port **443** (for ECR image pull, Secrets Manager, CloudWatch)
  - Outbound TCP on port **11211** (for ElastiCache Memcached)
  - Outbound TCP on port **1521** (for Oracle DB, if applicable)

### 3. IAM Roles

#### ECS Task Execution Role (`ecsTaskExecutionRole`)
This role allows ECS to pull images from ECR and write logs to CloudWatch.

```bash
# Create the role (if it doesn't exist)
aws iam create-role \
  --role-name ecsTaskExecutionRole \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "ecs-tasks.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }'

# Attach the managed policy
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy

# Add Secrets Manager access (for JWT_SIGNING_SECRET and DB password)
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite
```

#### ECS Task Role (`ecsTaskRole`)
This role grants the running container permissions to access AWS services.

```bash
aws iam create-role \
  --role-name ecsTaskRole \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "ecs-tasks.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }'
```

### 4. AWS Secrets Manager — Store Sensitive Values

```bash
# Store database password
aws secretsmanager create-secret \
  --name "resortslite/db-password" \
  --secret-string "YourActualDatabasePassword" \
  --region us-east-1

# Store JWT signing secret
aws secretsmanager create-secret \
  --name "resortslite/jwt-secret" \
  --secret-string "YourJWTSigningSecretMin32Chars!!" \
  --region us-east-1
```

### 5. CloudWatch Log Group

```bash
aws logs create-log-group --log-group-name /ecs/resortslite --region us-east-1
```

---

## ECS Task Definition Explained

The task definition (`ecs/task-definition.json`) configures how the container runs on Fargate:

| Field | Value | Notes |
|-------|-------|-------|
| `family` | `resortslite-task` | Task definition family name |
| `requiresCompatibilities` | `["FARGATE"]` | Fargate launch type |
| `networkMode` | `awsvpc` | Required for Fargate |
| `cpu` | `"512"` | 0.5 vCPU |
| `memory` | `"1024"` | 1 GB RAM |
| `executionRoleArn` | `ecsTaskExecutionRole` | Allows ECR pull + CloudWatch logs |
| `taskRoleArn` | `ecsTaskRole` | Container AWS API permissions |

### Valid Fargate CPU/Memory Combinations

| CPU | Valid Memory Values |
|-----|-------------------|
| 256 (.25 vCPU) | 512, 1024, 2048 MB |
| **512 (.5 vCPU)** | **1024, 2048, 3072, 4096 MB** ← Used |
| 1024 (1 vCPU) | 2048–8192 MB |
| 2048 (2 vCPU) | 4096–16384 MB |
| 4096 (4 vCPU) | 8192–30720 MB |

### Container Definition Highlights

- **Secrets**: `SPRING_DATASOURCE_PASSWORD` and `JWT_SIGNING_SECRET` are injected from AWS Secrets Manager (not environment variables) — they never appear in plaintext in the task definition.
- **Logging**: All container stdout/stderr is sent to CloudWatch Logs group `/ecs/resortslite`.
- **Port**: Container port `8080` mapped (no host port — Fargate uses awsvpc networking).

---

## ECS Service Configuration

The service definition (`ecs/service-definition.json`) controls how many tasks run and how they are networked:

| Field | Value | Notes |
|-------|-------|-------|
| `launchType` | `FARGATE` | Serverless compute |
| `desiredCount` | `2` | Two task replicas for HA |
| `networkMode` | `awsvpc` | Each task gets its own ENI |
| `assignPublicIp` | `ENABLED` | Required if using public subnets |
| `maximumPercent` | `200` | Rolling deploy: up to 4 tasks during update |
| `minimumHealthyPercent` | `50` | At least 1 task stays healthy during update |

### ALB Sticky Sessions (Transitional)

The application uses ALB sticky sessions as a transitional measure while Redis migration is pending. Configure the Target Group:

```bash
aws elbv2 modify-target-group-attributes \
  --target-group-arn <TARGET_GROUP_ARN> \
  --attributes \
    Key=stickiness.enabled,Value=true \
    Key=stickiness.type,Value=lb_cookie \
    Key=stickiness.lb_cookie.duration_seconds,Value=86400
```

---

## ECS Fargate Deployment Walkthrough

### Step 1: Build and Push the Image

```bash
./scripts/build-push.sh
# Select AWS ECR, enter your region, follow prompts
# Note the full image URI output (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest)
```

### Step 2: Run the Deploy Script

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

The script will prompt for:
- AWS region
- ECS cluster name
- ECR image URI (from Step 1)
- VPC ID
- Subnet IDs (comma-separated)
- Security Group ID
- Whether to create an ALB

### Step 3: Verify Deployment

```bash
# Check service status
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1

# View running tasks
aws ecs list-tasks \
  --cluster resortslite-cluster \
  --region us-east-1

# Tail application logs
aws logs tail /ecs/resortslite --follow --region us-east-1
```

### Step 4: Test the Application

```bash
# If ALB was created, use the DNS name from the deploy script output
curl http://<ALB_DNS>/actuator/health

# Expected response:
# {"status":"UP","components":{"db":{"status":"UP"},"diskSpace":{"status":"UP"}}}
```

---

## Environment Variables Reference

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | No | `docker` | Spring profile |
| `JAVA_OPTS` | No | See Dockerfile | JVM flags |
| `SERVER_PORT` | No | `8080` | Application port |
| `SPRING_DATASOURCE_URL` | Yes | — | JDBC connection URL |
| `SPRING_DATASOURCE_USERNAME` | Yes | — | DB username |
| `SPRING_DATASOURCE_PASSWORD` | Yes (secret) | — | DB password (from Secrets Manager) |
| `SPRING_DATASOURCE_DRIVER_CLASS_NAME` | No | `oracle.jdbc.OracleDriver` | JDBC driver |
| `SPRING_JPA_DATABASE_PLATFORM` | No | `Oracle12cDialect` | Hibernate dialect |
| `JWT_SIGNING_SECRET` | Yes (secret) | — | JWT HMAC-SHA256 key (from Secrets Manager) |
| `MEMCACHED_ENDPOINT` | No | `localhost:11211` | ElastiCache Memcached endpoint |
| `CACHE_TTL_SECONDS` | No | `3600` | Cache entry TTL in seconds |
| `REPORT_BASE_PATH` | No | `/mnt/efs/reports/` | EFS-backed report directory |
| `BACKUP_PATH` | No | `/mnt/efs/backups/nightly/` | EFS-backed backup directory |
| `PAYMENT_API_URL` | No | `http://payment-service:9090/payments/charge` | Payment service endpoint |
| `ALB_STICKY_SESSION_DURATION_SECONDS` | No | `86400` | ALB sticky session TTL |
| `TZ` | No | `UTC` | Container timezone |

---

## Secrets Management

**Never** store sensitive values as plaintext environment variables in the task definition. Use AWS Secrets Manager:

```bash
# Retrieve a secret value (for verification only)
aws secretsmanager get-secret-value \
  --secret-id resortslite/db-password \
  --region us-east-1 \
  --query SecretString --output text

# Rotate a secret
aws secretsmanager rotate-secret \
  --secret-id resortslite/jwt-secret \
  --region us-east-1
```

The task definition references secrets using the `secrets` array with `valueFrom` pointing to the Secrets Manager ARN. ECS injects these as environment variables at task startup.

---

## ECS-Specific Troubleshooting

### Task Fails to Start

```bash
# Check stopped task reason
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks <TASK_ARN> \
  --region us-east-1 \
  --query "tasks[0].{Status:lastStatus,StopReason:stoppedReason,Containers:containers[*].{Name:name,Reason:reason,ExitCode:exitCode}}"
```

Common causes:
- **ImagePullBackOff**: ECR permissions missing on `ecsTaskExecutionRole`, or wrong image URI
- **Essential container exited**: Application crashed — check CloudWatch logs
- **ResourceInitializationError**: Fargate agent cannot reach ECR/CloudWatch endpoints — check VPC endpoints or NAT Gateway

### Application Logs

```bash
# Stream logs in real time
aws logs tail /ecs/resortslite --follow --region us-east-1

# Search for errors
aws logs filter-log-events \
  --log-group-name /ecs/resortslite \
  --filter-pattern "ERROR" \
  --region us-east-1
```

### Network Issues

```bash
# Verify security group allows outbound 443 (ECR, Secrets Manager, CloudWatch)
aws ec2 describe-security-groups \
  --group-ids <SECURITY_GROUP_ID> \
  --query "SecurityGroups[0].IpPermissionsEgress"
```

### CPU/Memory Errors

If tasks are OOM-killed, increase memory in `task-definition.json`:
- Current: `cpu: "512"`, `memory: "1024"`
- Upgrade to: `cpu: "1024"`, `memory: "2048"`

Also tune JVM: `JAVA_OPTS=-Xmx768m -Xms256m -XX:MaxRAMPercentage=75.0`

### Memcached Connection Failures

The application logs a warning (not an error) if Memcached is unreachable — bookings still succeed but are not cached. Verify:
1. `MEMCACHED_ENDPOINT` points to the correct ElastiCache cluster endpoint
2. Security group allows outbound TCP 11211 to the ElastiCache subnet

### Database Connection Issues

```bash
# Test Oracle connectivity from within the VPC
# Ensure security group allows outbound TCP 1521 to the Oracle DB host
# Verify SPRING_DATASOURCE_URL uses the correct hostname/IP resolvable within the VPC
```

---

## Scaling and Management

### Manual Scaling

```bash
# Scale to 4 tasks
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --desired-count 4 \
  --region us-east-1
```

### Auto Scaling

```bash
# Register scalable target
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 2 \
  --max-capacity 10

# Create CPU-based scaling policy
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --policy-name resortslite-cpu-scaling \
  --policy-type TargetTrackingScaling \
  --target-tracking-scaling-policy-configuration '{
    "TargetValue": 70.0,
    "PredefinedMetricSpecification": {
      "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
    },
    "ScaleInCooldown": 300,
    "ScaleOutCooldown": 60
  }'
```

### Blue/Green Deployment

For zero-downtime deployments, use AWS CodeDeploy with ECS:

```bash
# Update service with new image (rolling update)
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --task-definition resortslite-task:<NEW_REVISION> \
  --region us-east-1

# Wait for stability
aws ecs wait services-stable \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1
```

### Force New Deployment (Restart Tasks)

```bash
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --force-new-deployment \
  --region us-east-1
```

---

## Security Considerations

1. **Secrets**: Always use AWS Secrets Manager for `SPRING_DATASOURCE_PASSWORD` and `JWT_SIGNING_SECRET`. Never hardcode credentials.
2. **Non-root container**: The Dockerfile creates and uses a non-root `appuser` — do not override with `--user root`.
3. **Network isolation**: Place Fargate tasks in private subnets with a NAT Gateway for outbound internet access. Use VPC endpoints for ECR, CloudWatch, and Secrets Manager to avoid internet traversal.
4. **Security Groups**: Restrict inbound to ALB only (not `0.0.0.0/0` directly to tasks).
5. **Image scanning**: Enable ECR image scanning on push to detect vulnerabilities.
6. **Log4j**: The `pom.xml` includes `log4j-core:2.14.1` which has CVE-2021-44228 (Log4Shell). **Upgrade to 2.17.1+ immediately** before production deployment.
7. **Commons Collections**: `commons-collections:3.2.1` has CVE-2015-6420. **Upgrade to 3.2.2+**.
8. **JWT secret length**: Ensure `JWT_SIGNING_SECRET` is at least 32 characters for HMAC-SHA256.
9. **HTTPS**: Configure HTTPS on the ALB listener (port 443) with an ACM certificate. Update internal service calls to use HTTPS.

---

## Java-Specific Notes

### JVM Container Awareness

The Dockerfile sets:
```
-XX:+UseContainerSupport        # Respect container CPU/memory limits
-XX:MaxRAMPercentage=75.0       # Use 75% of container memory for heap
-XX:+UnlockExperimentalVMOptions
-Djava.security.egd=file:/dev/./urandom  # Faster SecureRandom on Linux
```

With `memory: "1024"` (1 GB), the JVM heap will be ~768 MB maximum.

### Spring Boot Actuator

Health endpoint is exposed at `/actuator/health`. The ALB Target Group health check uses this path. Ensure `management.endpoints.web.exposure.include=health` is set (already configured in `application.properties`).

### Startup Time

Java 8 Spring Boot applications typically take 15–30 seconds to start. The ALB Target Group has:
- `healthCheckGracePeriodSeconds: 300` — gives the container 5 minutes before health checks begin
- `startPeriod: 60` — in docker-compose health check

### EFS Integration

The application writes reports to `REPORT_BASE_PATH` (default `/mnt/efs/reports/`). To mount EFS in ECS Fargate:

```json
// Add to task definition
"volumes": [{
  "name": "efs-reports",
  "efsVolumeConfiguration": {
    "fileSystemId": "fs-xxxxxxxx",
    "rootDirectory": "/reports",
    "transitEncryption": "ENABLED"
  }
}]

// Add to container definition
"mountPoints": [{
  "sourceVolume": "efs-reports",
  "containerPath": "/mnt/efs/reports",
  "readOnly": false
}]
```

### Oracle Database

The application is configured for Oracle 12c. Ensure:
- `ojdbc8` driver is on the classpath (included in `pom.xml`)
- `SPRING_DATASOURCE_URL` uses the format: `jdbc:oracle:thin:@<host>:<port>:<SID>` or `jdbc:oracle:thin:@//<host>:<port>/<service>`
- Oracle DB security group allows inbound TCP 1521 from the ECS task security group

### Memcached (ElastiCache)

Create an ElastiCache Memcached cluster in the same VPC:
```bash
aws elasticache create-cache-cluster \
  --cache-cluster-id resortslite-cache \
  --engine memcached \
  --cache-node-type cache.t3.micro \
  --num-cache-nodes 1 \
  --region us-east-1
```

Set `MEMCACHED_ENDPOINT` to the cluster's configuration endpoint (e.g., `resortslite-cache.abc123.cfg.use1.cache.amazonaws.com:11211`).
