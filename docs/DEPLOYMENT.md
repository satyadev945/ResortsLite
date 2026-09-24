# ResortsLite — AWS ECS Fargate Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Local Development with Docker Compose](#local-development-with-docker-compose)
4. [Build and Push Docker Image](#build-and-push-docker-image)
5. [AWS ECS Fargate Prerequisites](#aws-ecs-fargate-prerequisites)
6. [ECS Task Definition Explained](#ecs-task-definition-explained)
7. [ECS Service Configuration](#ecs-service-configuration)
8. [ECS Fargate Deployment Walkthrough](#ecs-fargate-deployment-walkthrough)
9. [ECS-Specific Troubleshooting](#ecs-specific-troubleshooting)
10. [ECS Fargate Scaling and Management](#ecs-fargate-scaling-and-management)
11. [Configuration Management](#configuration-management)
12. [Security Considerations](#security-considerations)
13. [Java-Specific Notes](#java-specific-notes)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.18  
**Java Version**: 8 (eclipse-temurin)  
**Build Tool**: Maven 3.9.4  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`  
**Target Platform**: AWS ECS Fargate  

ResortsLite is a legacy resort booking REST API modernised for cloud-native deployment on AWS ECS Fargate. It uses JWT-based stateless authentication, Amazon ElastiCache Memcached for distributed caching, and AWS Secrets Manager / SSM Parameter Store for secrets management.

---

## Prerequisites

### Local Development
- Docker Desktop 24+ (or Docker Engine 24+)
- Docker Compose v2.x
- Java 8 JDK (for local builds outside Docker)
- Maven 3.9+ (for local builds outside Docker)

### AWS Deployment
- AWS CLI v2 configured with appropriate credentials (`aws configure`)
- IAM permissions for: ECS, ECR, CloudWatch Logs, IAM, ELBv2, SSM, Secrets Manager
- An existing AWS VPC with at least two public or private subnets
- A security group allowing inbound TCP on port 8080 (and 80 if using ALB)

---

## Local Development with Docker Compose

### 1. Create a `.env` file in the project root

```bash
# .env — local development overrides (DO NOT commit to version control)
SPRING_DATASOURCE_URL=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1
SPRING_DATASOURCE_USERNAME=sa
SPRING_DATASOURCE_PASSWORD=
JWT_SECRET=local-dev-secret-replace-in-prod!!
MEMCACHED_ENDPOINT=
CACHE_TTL_SECONDS=3600
ALB_STICKY_DURATION_SECONDS=86400
REPORT_BASE_PATH=/tmp/reports
APP_PAYMENT_ENDPOINT=http://localhost:9090/charge
APP_INVENTORY_ENDPOINT=http://localhost:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://localhost:7070/send
```

### 2. Start the application

```bash
# Build and start
docker compose up --build

# Start in background
docker compose up -d --build

# View logs
docker compose logs -f resortsLite

# Stop
docker compose down
```

### 3. Verify the application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Create a booking
curl -X POST "http://localhost:8080/api/bookings/create?guestName=Alice&roomType=Suite&checkIn=2025-01-01&checkOut=2025-01-05"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=Suite"
```

---

## Build and Push Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
bash scripts/build-push.sh
```

The script will prompt you to:
1. Enter an image tag (default: `latest`)
2. Select a registry: **AWS ECR** or **Docker Hub**
3. Provide registry credentials / AWS region

### Windows

```cmd
scripts\build-push.bat
```

### Manual Build (AWS ECR example)

```bash
# Set variables
AWS_REGION=us-east-1
AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
IMAGE_TAG=1.0.0

# Authenticate to ECR
aws ecr get-login-password --region $AWS_REGION | \
  docker login --username AWS --password-stdin \
  ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com

# Create repository (first time only)
aws ecr create-repository --repository-name resortsLite --region $AWS_REGION

# Build and push
docker build -t ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/resortsLite:${IMAGE_TAG} .
docker push ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/resortsLite:${IMAGE_TAG}
```

---

## AWS ECS Fargate Prerequisites

### 1. IAM Roles

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

# Add SSM and Secrets Manager access (for secrets injection)
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMReadOnlyAccess
```

#### ECS Task Role (`ecsTaskRole`)
This role grants the running container permissions to call AWS services.

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

### 2. SSM Parameters and Secrets Manager

Store sensitive configuration before deploying:

```bash
AWS_REGION=us-east-1
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

# Database URL (SSM Parameter Store)
aws ssm put-parameter \
  --name "/resortsLite/prod/db/url" \
  --value "jdbc:oracle:thin:@your-db-host:1521:ORCL" \
  --type SecureString \
  --region $AWS_REGION

# Database username (SSM)
aws ssm put-parameter \
  --name "/resortsLite/prod/db/username" \
  --value "admin" \
  --type SecureString \
  --region $AWS_REGION

# Database password (Secrets Manager)
aws secretsmanager create-secret \
  --name "resortsLite/prod/db/password" \
  --secret-string "YourSecurePassword" \
  --region $AWS_REGION

# JWT signing secret (Secrets Manager)
aws secretsmanager create-secret \
  --name "resortsLite/prod/jwt/secret" \
  --secret-string "$(openssl rand -base64 48)" \
  --region $AWS_REGION

# Memcached endpoint (SSM)
aws ssm put-parameter \
  --name "/resortsLite/prod/memcached/endpoint" \
  --value "your-cluster.cfg.use1.cache.amazonaws.com:11211" \
  --type String \
  --region $AWS_REGION
```

### 3. CloudWatch Log Group

```bash
aws logs create-log-group \
  --log-group-name "/ecs/resortsLite" \
  --region $AWS_REGION

# Set retention (optional)
aws logs put-retention-policy \
  --log-group-name "/ecs/resortsLite" \
  --retention-in-days 30 \
  --region $AWS_REGION
```

### 4. Security Group

Ensure your security group allows:
- **Inbound**: TCP 8080 from ALB security group (or 0.0.0.0/0 for testing)
- **Inbound**: TCP 80 on ALB security group from 0.0.0.0/0
- **Outbound**: All traffic (for ECR image pull, CloudWatch, SSM, Secrets Manager)

---

## ECS Task Definition Explained

The task definition (`ecs/task-definition.json`) configures how the container runs on Fargate:

| Field | Value | Explanation |
|-------|-------|-------------|
| `requiresCompatibilities` | `["FARGATE"]` | Fargate launch type |
| `networkMode` | `awsvpc` | Required for Fargate; each task gets its own ENI |
| `cpu` | `"512"` | 0.5 vCPU |
| `memory` | `"1024"` | 1 GB RAM (valid Fargate combination) |
| `executionRoleArn` | `ecsTaskExecutionRole` | Allows ECS agent to pull images and write logs |
| `taskRoleArn` | `ecsTaskRole` | Grants the application container AWS API access |

### Container Definition Highlights

- **Port mapping**: Container port 8080 (no host port — Fargate manages networking)
- **Secrets injection**: Database credentials, JWT secret, and Memcached endpoint are injected from SSM Parameter Store and Secrets Manager at task startup
- **Logging**: CloudWatch Logs via `awslogs` driver → `/ecs/resortsLite`

### Valid Fargate CPU/Memory Combinations

| CPU | Valid Memory Options |
|-----|---------------------|
| 256 (.25 vCPU) | 512, 1024, 2048 MB |
| **512 (.5 vCPU)** | **1024**, 2048, 3072, 4096 MB |
| 1024 (1 vCPU) | 2048–8192 MB |
| 2048 (2 vCPU) | 4096–16384 MB |
| 4096 (4 vCPU) | 8192–30720 MB |

---

## ECS Service Configuration

The service definition (`ecs/service-definition.json`) controls how tasks are scheduled:

| Field | Value | Explanation |
|-------|-------|-------------|
| `launchType` | `FARGATE` | Serverless compute |
| `desiredCount` | `2` | Two tasks for high availability |
| `assignPublicIp` | `ENABLED` | Required if tasks are in public subnets |
| `maximumPercent` | `200` | Up to 4 tasks during rolling deployment |
| `minimumHealthyPercent` | `50` | At least 1 task stays healthy during deployment |

---

## ECS Fargate Deployment Walkthrough

### Step 1: Push your image to ECR

```bash
bash scripts/build-push.sh
# Select option 1 (AWS ECR) and follow prompts
```

### Step 2: Run the deployment script

```bash
chmod +x scripts/deploy-image.sh
bash scripts/deploy-image.sh
```

The script will:
1. Prompt for AWS region, cluster name, image URI, VPC, subnets, and security group
2. Resolve your AWS Account ID automatically
3. Create the CloudWatch log group if it doesn't exist
4. Create the ECS cluster if it doesn't exist
5. Optionally create an Application Load Balancer and Target Group
6. Register the task definition with resolved placeholders
7. Create or update the ECS service
8. Wait for the service to stabilise
9. Print the service status and access URLs

### Step 3: Verify the deployment

```bash
# Check service status
aws ecs describe-services \
  --cluster resortsLite-cluster \
  --services resortsLite-service \
  --region us-east-1

# View running tasks
aws ecs list-tasks \
  --cluster resortsLite-cluster \
  --service-name resortsLite-service \
  --region us-east-1

# Tail application logs
aws logs tail /ecs/resortsLite --follow --region us-east-1
```

### Step 4: Test the application

```bash
# If using ALB
ALB_DNS=<your-alb-dns-from-deploy-output>
curl http://$ALB_DNS/actuator/health

# Create a booking
curl -X POST "http://$ALB_DNS/api/bookings/create?guestName=Alice&roomType=Suite&checkIn=2025-01-01&checkOut=2025-01-05"
```

---

## ECS-Specific Troubleshooting

### Task fails to start

```bash
# Check stopped task reason
aws ecs describe-tasks \
  --cluster resortsLite-cluster \
  --tasks <TASK_ARN> \
  --region us-east-1 \
  --query "tasks[0].{Status:lastStatus,StopReason:stoppedReason,Containers:containers[*].{Name:name,Reason:reason,ExitCode:exitCode}}"
```

**Common causes:**
- `CannotPullContainerError`: ECR authentication issue or image not found → verify `executionRoleArn` has ECR permissions
- `ResourceInitializationError`: Secrets Manager / SSM access denied → check `executionRoleArn` policies
- `OutOfMemoryError` in logs: Increase task memory in `task-definition.json`
- Application fails to start: Check CloudWatch logs for Java exceptions

### Network connectivity issues

```bash
# Verify security group allows outbound HTTPS (443) for ECR, SSM, Secrets Manager
aws ec2 describe-security-groups --group-ids <SG_ID> --region us-east-1

# Check VPC endpoints (recommended for private subnets)
# Required endpoints: ecr.api, ecr.dkr, logs, ssm, secretsmanager, s3
```

### CPU/Memory errors

- `RESOURCE:MEMORY` or `RESOURCE:CPU`: Increase values in `task-definition.json`
- Ensure you use valid Fargate CPU/memory combinations (see table above)

### Service not stabilising

```bash
# Check service events
aws ecs describe-services \
  --cluster resortsLite-cluster \
  --services resortsLite-service \
  --region us-east-1 \
  --query "services[0].events[:5]"
```

### ALB health check failures

- Verify the security group allows traffic from the ALB to the container on port 8080
- Check that `/actuator/health` returns HTTP 200
- Increase `healthCheckGracePeriodSeconds` if the JVM takes longer to start (default: 300s)

---

## ECS Fargate Scaling and Management

### Manual scaling

```bash
aws ecs update-service \
  --cluster resortsLite-cluster \
  --service resortsLite-service \
  --desired-count 4 \
  --region us-east-1
```

### Auto Scaling

```bash
# Register scalable target
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --resource-id service/resortsLite-cluster/resortsLite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 2 \
  --max-capacity 10 \
  --region us-east-1

# Create CPU-based scaling policy
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --resource-id service/resortsLite-cluster/resortsLite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --policy-name resortsLite-cpu-scaling \
  --policy-type TargetTrackingScaling \
  --target-tracking-scaling-policy-configuration '{
    "TargetValue": 70.0,
    "PredefinedMetricSpecification": {
      "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
    },
    "ScaleInCooldown": 300,
    "ScaleOutCooldown": 60
  }' \
  --region us-east-1
```

### Blue/Green Deployment with CodeDeploy

For zero-downtime deployments, configure AWS CodeDeploy with ECS:
1. Create a CodeDeploy application and deployment group for ECS
2. Set `deploymentController.type` to `CODE_DEPLOY` in the service definition
3. Use `appspec.yaml` to define the deployment lifecycle

---

## Configuration Management

### Environment Variables Reference

| Variable | Source | Description |
|----------|--------|-------------|
| `SPRING_PROFILES_ACTIVE` | Task definition | Spring profile (`docker`) |
| `JAVA_OPTS` | Task definition | JVM memory and GC settings |
| `SPRING_DATASOURCE_URL` | SSM Parameter Store | Oracle JDBC connection URL |
| `SPRING_DATASOURCE_USERNAME` | SSM Parameter Store | Database username |
| `SPRING_DATASOURCE_PASSWORD` | Secrets Manager | Database password |
| `JWT_SECRET` | Secrets Manager | JWT signing secret (min 32 bytes) |
| `MEMCACHED_ENDPOINT` | SSM Parameter Store | ElastiCache Memcached endpoint |
| `CACHE_TTL_SECONDS` | Task definition | Cache entry TTL (default: 3600) |
| `ALB_STICKY_DURATION_SECONDS` | Task definition | ALB sticky session duration |
| `REPORT_BASE_PATH` | Task definition | EFS mount path for reports |
| `TZ` | Task definition | Timezone (UTC) |

### Updating configuration

```bash
# Update an SSM parameter
aws ssm put-parameter \
  --name "/resortsLite/prod/memcached/endpoint" \
  --value "new-cluster.cfg.use1.cache.amazonaws.com:11211" \
  --type String \
  --overwrite \
  --region us-east-1

# Force new deployment to pick up changes
aws ecs update-service \
  --cluster resortsLite-cluster \
  --service resortsLite-service \
  --force-new-deployment \
  --region us-east-1
```

---

## Security Considerations

1. **Secrets Management**: Never hardcode credentials. Use AWS Secrets Manager for passwords and JWT secrets; use SSM Parameter Store for non-sensitive configuration.

2. **Non-root Container**: The Dockerfile creates a dedicated `appuser` account. The container never runs as root.

3. **Network Isolation**: Deploy tasks in private subnets with a NAT Gateway for outbound traffic. Use VPC endpoints for ECR, SSM, Secrets Manager, and CloudWatch to avoid internet exposure.

4. **Security Groups**: Apply least-privilege security group rules. Only allow inbound traffic from the ALB security group on port 8080.

5. **Image Scanning**: Enable ECR image scanning on push:
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name resortsLite \
     --image-scanning-configuration scanOnPush=true \
     --region us-east-1
   ```

6. **Log4Shell Mitigation**: The `pom.xml` includes `log4j-core 2.14.1` which has CVE-2021-44228. **Upgrade to log4j-core 2.17.1+** before production deployment.

7. **Commons Collections**: `commons-collections 3.2.1` has CVE-2015-6420. **Upgrade to 3.2.2+**.

8. **IAM Least Privilege**: Scope `ecsTaskRole` permissions to only the AWS services the application needs (ElastiCache, SSM, Secrets Manager).

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with:
```
-Xms256m -Xmx512m
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0
-XX:+UnlockExperimentalVMOptions
```

- `UseContainerSupport`: Enables JVM awareness of container memory limits (Java 8u191+)
- `MaxRAMPercentage=75.0`: JVM uses up to 75% of container memory for heap
- With 1024 MB task memory, effective heap ceiling is ~768 MB

### Spring Boot Actuator

The `/actuator/health` endpoint is exposed and used by the ALB Target Group health check. The endpoint returns:
```json
{"status": "UP", "components": {...}}
```

### Graceful Shutdown

Spring Boot 2.7 handles `SIGTERM` gracefully. ECS sends `SIGTERM` before `SIGKILL` (default 30-second grace period). To extend:
```json
"stopTimeout": 60
```
Add this to the container definition in `task-definition.json`.

### Oracle JDBC

The application uses Oracle JDBC (`ojdbc8`). Ensure the Oracle DB is accessible from the ECS task's VPC/subnet. The connection URL is injected from SSM Parameter Store.

### ElastiCache Memcached

If `MEMCACHED_ENDPOINT` is not set, the application starts without distributed caching (graceful degradation). For production, always configure the ElastiCache endpoint.

### EFS for Reports

The `/api/bookings/report/download` endpoint uses `REPORT_BASE_PATH` (default `/mnt/efs/reports`). To mount an EFS volume:
1. Create an EFS file system in the same VPC
2. Add a volume to the task definition:
   ```json
   "volumes": [{
     "name": "efs-reports",
     "efsVolumeConfiguration": {
       "fileSystemId": "fs-xxxxxxxx",
       "rootDirectory": "/reports"
     }
   }]
   ```
3. Add a mount point to the container definition:
   ```json
   "mountPoints": [{
     "sourceVolume": "efs-reports",
     "containerPath": "/mnt/efs/reports",
     "readOnly": false
   }]
   ```
