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
11. [ECS-Specific Troubleshooting](#ecs-specific-troubleshooting)
12. [Scaling and Management](#scaling-and-management)
13. [Security Considerations](#security-considerations)
14. [Java-Specific Notes](#java-specific-notes)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.18  
**Java Version**: 8  
**Build Tool**: Maven  
**Package Type**: JAR (executable fat JAR)  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`  
**Target Platform**: AWS ECS Fargate  

ResortsLite is a legacy resort booking application modernised for cloud-native deployment on AWS ECS Fargate. It uses stateless JWT authentication, Amazon ElastiCache (Memcached) for distributed caching, and ECS Service Connect for inter-service communication.

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker Desktop | 24.x+ | Build and run containers |
| Docker Compose | 2.x+ | Local multi-container orchestration |
| Java JDK | 8+ | Local development (optional) |
| Maven | 3.9.x | Local builds (optional) |

### AWS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS resource management |
| Docker | 24.x+ | Image build and push |
| Python 3 | 3.8+ | Used by deploy-image.sh for JSON manipulation |

---

## Project Structure

```
min-comp-5/
├── Dockerfile                    # Multi-stage build (Maven builder + JDK runtime)
├── docker-compose.yml            # Local development (application only)
├── .dockerignore                 # Excludes target/, wrapper files, IDE files
├── pom.xml                       # Maven project descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   ├── JwtUtil.java
│       │   └── ReportService.java
│       └── resources/
│           └── application.properties
├── ecs/
│   ├── task-definition.json      # ECS Fargate task definition
│   └── service-definition.json   # ECS Fargate service definition
├── scripts/
│   ├── build-push.sh             # Linux/macOS: build and push to ECR or Docker Hub
│   ├── build-push.bat            # Windows: build and push to ECR or Docker Hub
│   ├── deploy-image.sh           # Linux/macOS: deploy to ECS Fargate
│   └── deploy-image.bat          # Windows: deploy to ECS Fargate
└── docs/
    └── DEPLOYMENT.md             # This file
```

---

## Local Development with Docker Compose

### 1. Clone and configure

```bash
git clone <repository-url>
cd min-comp-5
```

### 2. Create a local `.env` file (optional overrides)

```bash
cat > .env <<'EOF'
JWT_SECRET=my-local-dev-secret-at-least-32-chars
MEMCACHED_ENDPOINT=localhost:11211
INVENTORY_SERVICE_URL=http://localhost:8081
PAYMENT_SERVICE_URL=http://localhost:9090/payments/charge
DB_HOST=localhost
APP_REPORT_BASE_PATH=/tmp/reports
EOF
```

### 3. Build and start

```bash
docker compose up --build
```

### 4. Verify the application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Create a booking
curl -X POST "http://localhost:8080/api/bookings/create?guestName=Alice&roomType=DELUXE&checkIn=2025-01-10&checkOut=2025-01-15"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=SUITE"
```

### 5. Stop

```bash
docker compose down
```

---

## Build and Push Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
bash scripts/build-push.sh
```

### Windows

```cmd
scripts\build-push.bat
```

The script will prompt you to:
1. Enter an image tag (defaults to `latest`)
2. Select registry: **1) AWS ECR** or **2) Docker Hub**
3. Provide registry-specific credentials

**AWS ECR flow:**
- Prompts for AWS Region and ECR repository name
- Automatically fetches your Account ID
- Logs in to ECR
- Auto-creates the ECR repository if it does not exist
- Builds and pushes the image

**Docker Hub flow:**
- Prompts for username, password/token, and namespace
- Logs in to Docker Hub
- Builds and pushes the image

> **Note**: The image name is automatically sanitised to lowercase with hyphens (e.g., `resortsLite` → `resortslite`).

---

## AWS ECS Fargate Prerequisites

### 1. AWS CLI Configuration

```bash
aws configure
# Enter: Access Key ID, Secret Access Key, Region, Output format (json)
```

### 2. VPC and Networking

Ensure you have:
- A VPC with at least **2 public or private subnets** in different Availability Zones
- A **Security Group** that allows:
  - Inbound TCP 8080 from your ALB security group (or 0.0.0.0/0 for testing)
  - Outbound all traffic (for ECR image pull, CloudWatch logs, SSM)

```bash
# List your VPCs
aws ec2 describe-vpcs --query "Vpcs[*].{ID:VpcId,CIDR:CidrBlock}" --output table

# List subnets
aws ec2 describe-subnets --query "Subnets[*].{ID:SubnetId,AZ:AvailabilityZone,CIDR:CidrBlock}" --output table
```

### 3. IAM Roles

#### ECS Task Execution Role (required)
This role allows ECS to pull images from ECR and write logs to CloudWatch.

```bash
# Create the role
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

# Add Secrets Manager and SSM permissions (for JWT_SECRET and MEMCACHED_ENDPOINT)
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite

aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMReadOnlyAccess
```

#### ECS Task Role (optional, for application AWS SDK calls)

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

### 4. AWS Secrets Manager — JWT Secret

```bash
aws secretsmanager create-secret \
  --name "resortslite/jwt-secret" \
  --secret-string "$(openssl rand -base64 48)" \
  --region us-east-1
```

### 5. AWS SSM Parameter Store — Memcached Endpoint

```bash
aws ssm put-parameter \
  --name "/resortslite/memcached/endpoint" \
  --value "<elasticache-cluster-endpoint>:11211" \
  --type String \
  --region us-east-1
```

### 6. CloudWatch Log Group

```bash
aws logs create-log-group \
  --log-group-name "/ecs/resortsLite" \
  --region us-east-1
```

---

## ECS Task Definition Explained

File: `ecs/task-definition.json`

| Field | Value | Notes |
|-------|-------|-------|
| `family` | `resortsLite-task` | Task definition family name |
| `requiresCompatibilities` | `["FARGATE"]` | Fargate launch type |
| `networkMode` | `awsvpc` | Required for Fargate |
| `cpu` | `"512"` | 0.5 vCPU |
| `memory` | `"1024"` | 1 GB RAM |
| `executionRoleArn` | `ecsTaskExecutionRole` | ECR pull + CloudWatch logs |
| `taskRoleArn` | `ecsTaskRole` | Application AWS SDK calls |

### Container Definition Highlights

- **Image**: Replaced at deploy time via `{{IMAGE_URI}}` placeholder
- **Port**: 8080 (TCP)
- **Secrets**: `JWT_SECRET` from Secrets Manager, `MEMCACHED_ENDPOINT` from SSM
- **Logging**: CloudWatch Logs via `awslogs` driver → `/ecs/resortsLite`
- **EFS Volume**: `/mnt/efs/reports` mounted from EFS for report storage

### Valid Fargate CPU/Memory Combinations

| CPU | Memory Options |
|-----|---------------|
| 256 | 512, 1024, 2048 MB |
| **512** | **1024**, 2048, 3072, 4096 MB |
| 1024 | 2048–8192 MB |
| 2048 | 4096–16384 MB |
| 4096 | 8192–30720 MB |

> The task definition uses **cpu: "512", memory: "1024"** — a valid combination.

---

## ECS Service Configuration

File: `ecs/service-definition.json`

| Field | Value | Notes |
|-------|-------|-------|
| `serviceName` | `resortsLite-service` | ECS service name |
| `launchType` | `FARGATE` | Serverless compute |
| `desiredCount` | `2` | Two tasks for HA |
| `networkMode` | `awsvpc` | Each task gets its own ENI |
| `assignPublicIp` | `ENABLED` | Required for public subnet ECR pull |
| `maximumPercent` | `200` | Rolling deploy: up to 4 tasks |
| `minimumHealthyPercent` | `50` | At least 1 task always running |

---

## ECS Fargate Deployment Walkthrough

### Step 1: Build and push the image

```bash
bash scripts/build-push.sh
# Select: 1 (AWS ECR)
# Enter your region, ECR repo name
# Image will be built and pushed
```

### Step 2: Note the full image URI

```
123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
```

### Step 3: Run the deployment script

```bash
chmod +x scripts/deploy-image.sh
bash scripts/deploy-image.sh
```

You will be prompted for:
- AWS Region
- ECS Cluster name
- ECR Image URI (from Step 2)
- VPC ID
- Subnet IDs (comma-separated, at least 2)
- Security Group ID
- Whether to create an Application Load Balancer

### Step 4: Monitor deployment

```bash
# Watch service events
aws ecs describe-services \
  --cluster resortsLite-cluster \
  --services resortsLite-service \
  --region us-east-1

# Tail CloudWatch logs
aws logs tail /ecs/resortsLite --follow --region us-east-1
```

### Step 5: Verify health

```bash
# Get task public IP (if using public subnets)
TASK_ARN=$(aws ecs list-tasks \
  --cluster resortsLite-cluster \
  --service-name resortsLite-service \
  --region us-east-1 \
  --query "taskArns[0]" --output text)

ENI_ID=$(aws ecs describe-tasks \
  --cluster resortsLite-cluster \
  --tasks $TASK_ARN \
  --region us-east-1 \
  --query "tasks[0].attachments[0].details[?name=='networkInterfaceId'].value" \
  --output text)

PUBLIC_IP=$(aws ec2 describe-network-interfaces \
  --network-interface-ids $ENI_ID \
  --query "NetworkInterfaces[0].Association.PublicIp" \
  --output text)

curl http://$PUBLIC_IP:8080/actuator/health
```

---

## Environment Variables Reference

| Variable | Source | Description |
|----------|--------|-------------|
| `SPRING_PROFILES_ACTIVE` | Task definition | Spring profile (`docker`) |
| `SERVER_PORT` | Task definition | Application port (8080) |
| `JWT_SECRET` | Secrets Manager | JWT signing secret (min 32 chars) |
| `MEMCACHED_ENDPOINT` | SSM Parameter Store | ElastiCache endpoint (`host:11211`) |
| `INVENTORY_SERVICE_URL` | Task definition | Inventory service base URL |
| `PAYMENT_SERVICE_URL` | Task definition | Payment service charge endpoint |
| `DB_HOST` | Task definition | Database host (RDS endpoint or Service Connect) |
| `APP_REPORT_BASE_PATH` | Task definition | EFS mount path for reports |
| `JAVA_OPTS` | Task definition | JVM flags |
| `TZ` | Task definition | Timezone (UTC) |

---

## ECS-Specific Troubleshooting

### Task fails to start — `CannotPullContainerError`

```bash
# Check execution role has ECR permissions
aws iam list-attached-role-policies --role-name ecsTaskExecutionRole

# Verify ECR repository exists
aws ecr describe-repositories --repository-names resortslite --region us-east-1

# Check security group allows outbound HTTPS (443) for ECR pull
```

### Task stops immediately — `Essential container exited`

```bash
# View stopped task logs
aws ecs describe-tasks \
  --cluster resortsLite-cluster \
  --tasks <TASK_ARN> \
  --region us-east-1 \
  --query "tasks[0].stoppedReason"

# Check CloudWatch logs
aws logs get-log-events \
  --log-group-name /ecs/resortsLite \
  --log-stream-name ecs/resortsLite/<TASK_ID> \
  --region us-east-1
```

### `InvalidParameterException: cpu/memory combination`

Ensure you use valid Fargate combinations. The default `cpu: "512", memory: "1024"` is valid.

### Secrets not injected — `ResourceNotFoundException`

```bash
# Verify secret exists
aws secretsmanager describe-secret --secret-id resortslite/jwt-secret --region us-east-1

# Verify SSM parameter exists
aws ssm get-parameter --name /resortslite/memcached/endpoint --region us-east-1

# Verify execution role has access
aws iam simulate-principal-policy \
  --policy-source-arn arn:aws:iam::<ACCOUNT>:role/ecsTaskExecutionRole \
  --action-names secretsmanager:GetSecretValue \
  --resource-arns arn:aws:secretsmanager:us-east-1:<ACCOUNT>:secret:resortslite/jwt-secret
```

### Service not stabilising — tasks cycling

```bash
# Check service events
aws ecs describe-services \
  --cluster resortsLite-cluster \
  --services resortsLite-service \
  --region us-east-1 \
  --query "services[0].events[:5]"

# Increase startPeriod in health check if JVM startup is slow
# Default startPeriod is 60s — increase to 120s for slow environments
```

### Network connectivity issues

- Ensure security group allows **inbound TCP 8080** from ALB or your IP
- Ensure security group allows **outbound TCP 443** for ECR image pull
- Ensure security group allows **outbound TCP 11211** to ElastiCache
- For private subnets: ensure NAT Gateway or VPC endpoints for ECR, S3, CloudWatch, SSM

---

## Scaling and Management

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
  --max-capacity 10

# CPU-based scaling policy
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
  }'
```

### Blue/Green Deployment with CodeDeploy

1. Enable CodeDeploy in the ECS service definition
2. Create a CodeDeploy application and deployment group
3. Use `appspec.yaml` to define traffic shifting strategy
4. Trigger deployments via CodePipeline or CLI

### Force new deployment (rolling update)

```bash
aws ecs update-service \
  --cluster resortsLite-cluster \
  --service resortsLite-service \
  --force-new-deployment \
  --region us-east-1
```

### Stop all tasks (scale to zero)

```bash
aws ecs update-service \
  --cluster resortsLite-cluster \
  --service resortsLite-service \
  --desired-count 0 \
  --region us-east-1
```

---

## Security Considerations

1. **JWT Secret**: Always use a strong random secret (≥32 chars) stored in AWS Secrets Manager. Never commit secrets to source control.

2. **Non-root container**: The Dockerfile creates a dedicated `appuser` account. The container runs as non-root.

3. **No hardcoded credentials**: All sensitive values (`JWT_SECRET`, `MEMCACHED_ENDPOINT`) are injected at runtime from Secrets Manager / SSM.

4. **Security Groups**: Restrict inbound access to port 8080 to the ALB security group only. Never expose 8080 directly to 0.0.0.0/0 in production.

5. **VPC isolation**: Deploy ECS tasks in private subnets with a NAT Gateway for outbound internet access. Use VPC endpoints for ECR, S3, CloudWatch, and SSM to avoid internet traversal.

6. **EFS encryption**: The task definition enables `transitEncryption: ENABLED` for the EFS volume mount.

7. **Image scanning**: Enable ECR image scanning on push:
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name resortslite \
     --image-scanning-configuration scanOnPush=true \
     --region us-east-1
   ```

8. **Log retention**: Set a retention policy on the CloudWatch log group:
   ```bash
   aws logs put-retention-policy \
     --log-group-name /ecs/resortsLite \
     --retention-in-days 30 \
     --region us-east-1
   ```

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with:
```
-Xmx512m -Xms256m
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0
-XX:+ExitOnOutOfMemoryError
```

- `UseContainerSupport`: Enables JVM to respect container memory limits (Java 8u191+)
- `MaxRAMPercentage=75.0`: JVM heap uses up to 75% of container memory (768 MB of 1024 MB)
- `ExitOnOutOfMemoryError`: Causes the container to exit (and ECS to restart it) on OOM

### Spring Boot Actuator

The application exposes:
- `GET /actuator/health` — liveness and readiness probe
- `GET /actuator/info` — application metadata

### Spring Profiles

Set `SPRING_PROFILES_ACTIVE=docker` in the task definition. Create `application-docker.properties` or `application-docker.yml` for environment-specific overrides.

### Graceful Shutdown

Spring Boot 2.7.x supports graceful shutdown. Add to `application.properties`:
```properties
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=30s
```

The Dockerfile uses `STOPSIGNAL SIGTERM` and `exec java ...` to ensure the JVM receives SIGTERM and can complete in-flight requests before shutdown.

### H2 In-Memory Database

The current configuration uses H2 in-memory database (`jdbc:h2:mem:resortdb`). For production:
- Replace with Amazon RDS (PostgreSQL or MySQL)
- Update `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`
- Store credentials in AWS Secrets Manager
- Reference them in the ECS task definition `secrets` array

### ElastiCache Memcached

The application uses `spymemcached` to connect to Amazon ElastiCache. Ensure:
1. ElastiCache cluster is in the same VPC as ECS tasks
2. Security group allows TCP 11211 from ECS task security group
3. `MEMCACHED_ENDPOINT` is set to `<cluster-endpoint>:11211` in SSM Parameter Store
