# ResortsLite - AWS ECS Fargate Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Local Development Setup](#local-development-setup)
4. [Building and Pushing Docker Images](#building-and-pushing-docker-images)
5. [AWS ECS Fargate Prerequisites](#aws-ecs-fargate-prerequisites)
6. [ECS Task Definition Explained](#ecs-task-definition-explained)
7. [ECS Service Configuration](#ecs-service-configuration)
8. [Deployment to AWS ECS Fargate](#deployment-to-aws-ecs-fargate)
9. [Configuration Management](#configuration-management)
10. [Monitoring and Logging](#monitoring-and-logging)
11. [Troubleshooting](#troubleshooting)
12. [Security Best Practices](#security-best-practices)
13. [Scaling and Performance](#scaling-and-performance)

---

## Overview

ResortsLite is a Spring Boot 2.7.18 application built with Java 8, designed for containerized deployment on AWS ECS Fargate. This guide provides comprehensive instructions for building, deploying, and managing the application in a cloud-native environment.

### Technology Stack
- **Framework**: Spring Boot 2.7.18
- **Java Version**: Java 8 (1.8)
- **Build Tool**: Maven 3.9.4
- **Database**: Oracle Database (JDBC)
- **Caching**: AWS ElastiCache Memcached
- **Authentication**: JWT (JSON Web Tokens)
- **Container Platform**: Docker
- **Deployment Platform**: AWS ECS Fargate
- **Monitoring**: Spring Boot Actuator + CloudWatch

### Application Architecture
- **Port**: 8080 (configurable via `SERVER_PORT` environment variable)
- **Health Endpoint**: `/actuator/health`
- **Info Endpoint**: `/actuator/info`
- **Package Type**: Executable JAR

---

## Prerequisites

### Required Software
1. **Docker Desktop** (version 20.10 or later)
   - Download: https://www.docker.com/products/docker-desktop
   - Verify: `docker --version`

2. **AWS CLI** (version 2.x)
   - Download: https://aws.amazon.com/cli/
   - Verify: `aws --version`
   - Configure: `aws configure`

3. **Git** (for version control)
   - Download: https://git-scm.com/
   - Verify: `git --version`

4. **Java Development Kit 8** (for local development)
   - Download: https://adoptium.net/
   - Verify: `java -version`

5. **Maven 3.6+** (for local builds)
   - Download: https://maven.apache.org/download.cgi
   - Verify: `mvn --version`

### AWS Account Requirements
- Active AWS account with appropriate permissions
- IAM user with programmatic access (Access Key ID and Secret Access Key)
- Permissions required:
  - ECS full access
  - ECR full access
  - CloudWatch Logs write access
  - VPC and networking permissions
  - IAM role creation (for ECS task execution)

---

## Local Development Setup

### 1. Clone the Repository
```bash
git clone <repository-url>
cd var
```

### 2. Configure Application Properties
Edit `src/main/resources/application.properties` for local development:

```properties
# Local development port
server.port=8080

# Local database configuration (use H2 for local testing)
spring.datasource.url=jdbc:h2:mem:testdb
spring.datasource.username=sa
spring.datasource.password=
spring.datasource.driver-class-name=org.h2.Driver

# H2 Console (for debugging)
spring.h2.console.enabled=true

# JPA configuration
spring.jpa.show-sql=true
spring.jpa.hibernate.ddl-auto=create-drop
```

### 3. Build the Application Locally
```bash
# Clean and build
mvn clean package -DskipTests

# Run locally
java -jar target/resortsLite-1.0.0.jar
```

### 4. Test Local Application
```bash
# Health check
curl http://localhost:8080/actuator/health

# Expected response
{"status":"UP"}
```

### 5. Run with Docker Compose (Local Testing)
```bash
# Build and start
docker-compose up --build

# Stop
docker-compose down
```

---

## Building and Pushing Docker Images

### Option 1: Using Build Script (Recommended)

#### Linux/macOS
```bash
cd scripts
chmod +x build-push.sh
./build-push.sh
```

#### Windows
```cmd
cd scripts
build-push.bat
```

The script will prompt you for:
1. **Registry Type**: Choose AWS ECR or Docker Hub
2. **Image Tag**: Enter a version tag (default: `latest`)
3. **Registry Credentials**: Provide AWS region or Docker Hub credentials

### Option 2: Manual Docker Build

#### Build Image
```bash
# Build the Docker image
docker build -t resortslite:latest .

# Verify image
docker images | grep resortslite
```

#### Push to AWS ECR
```bash
# Set variables
AWS_REGION=us-east-1
AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_REPO=resortslite

# Login to ECR
aws ecr get-login-password --region $AWS_REGION | \
  docker login --username AWS --password-stdin \
  $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com

# Create ECR repository (if not exists)
aws ecr create-repository \
  --repository-name $ECR_REPO \
  --region $AWS_REGION \
  --image-scanning-configuration scanOnPush=true

# Tag image
docker tag resortslite:latest \
  $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:latest

# Push image
docker push $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:latest
```

#### Push to Docker Hub
```bash
# Login to Docker Hub
docker login

# Tag image
docker tag resortslite:latest <your-username>/resortslite:latest

# Push image
docker push <your-username>/resortslite:latest
```

---

## AWS ECS Fargate Prerequisites

### 1. VPC and Networking Setup

#### Create VPC (if not exists)
```bash
# Create VPC
VPC_ID=$(aws ec2 create-vpc \
  --cidr-block 10.0.0.0/16 \
  --region us-east-1 \
  --query 'Vpc.VpcId' \
  --output text)

# Enable DNS hostnames
aws ec2 modify-vpc-attribute \
  --vpc-id $VPC_ID \
  --enable-dns-hostnames

# Create Internet Gateway
IGW_ID=$(aws ec2 create-internet-gateway \
  --region us-east-1 \
  --query 'InternetGateway.InternetGatewayId' \
  --output text)

# Attach Internet Gateway to VPC
aws ec2 attach-internet-gateway \
  --vpc-id $VPC_ID \
  --internet-gateway-id $IGW_ID
```

#### Create Subnets (at least 2 for high availability)
```bash
# Create public subnet 1 (us-east-1a)
SUBNET_1=$(aws ec2 create-subnet \
  --vpc-id $VPC_ID \
  --cidr-block 10.0.1.0/24 \
  --availability-zone us-east-1a \
  --query 'Subnet.SubnetId' \
  --output text)

# Create public subnet 2 (us-east-1b)
SUBNET_2=$(aws ec2 create-subnet \
  --vpc-id $VPC_ID \
  --cidr-block 10.0.2.0/24 \
  --availability-zone us-east-1b \
  --query 'Subnet.SubnetId' \
  --output text)

# Enable auto-assign public IP
aws ec2 modify-subnet-attribute \
  --subnet-id $SUBNET_1 \
  --map-public-ip-on-launch

aws ec2 modify-subnet-attribute \
  --subnet-id $SUBNET_2 \
  --map-public-ip-on-launch
```

#### Create Route Table
```bash
# Create route table
RT_ID=$(aws ec2 create-route-table \
  --vpc-id $VPC_ID \
  --query 'RouteTable.RouteTableId' \
  --output text)

# Add route to Internet Gateway
aws ec2 create-route \
  --route-table-id $RT_ID \
  --destination-cidr-block 0.0.0.0/0 \
  --gateway-id $IGW_ID

# Associate subnets with route table
aws ec2 associate-route-table \
  --subnet-id $SUBNET_1 \
  --route-table-id $RT_ID

aws ec2 associate-route-table \
  --subnet-id $SUBNET_2 \
  --route-table-id $RT_ID
```

### 2. Security Group Configuration

```bash
# Create security group
SG_ID=$(aws ec2 create-security-group \
  --group-name resortslite-sg \
  --description "Security group for ResortsLite ECS tasks" \
  --vpc-id $VPC_ID \
  --query 'GroupId' \
  --output text)

# Allow inbound HTTP traffic on port 8080
aws ec2 authorize-security-group-ingress \
  --group-id $SG_ID \
  --protocol tcp \
  --port 8080 \
  --cidr 0.0.0.0/0

# Allow inbound HTTP traffic on port 80 (for ALB)
aws ec2 authorize-security-group-ingress \
  --group-id $SG_ID \
  --protocol tcp \
  --port 80 \
  --cidr 0.0.0.0/0

# Allow all outbound traffic (default)
```

### 3. IAM Roles Setup

#### ECS Task Execution Role
This role allows ECS to pull images from ECR and write logs to CloudWatch.

```bash
# Create trust policy
cat > ecs-task-execution-trust-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ecs-tasks.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF

# Create role
aws iam create-role \
  --role-name ecsTaskExecutionRole \
  --assume-role-policy-document file://ecs-task-execution-trust-policy.json

# Attach AWS managed policy
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
```

#### ECS Task Role (Optional)
This role grants permissions to the application running in the container.

```bash
# Create task role
aws iam create-role \
  --role-name ecsTaskRole \
  --assume-role-policy-document file://ecs-task-execution-trust-policy.json

# Attach policies as needed (e.g., S3 access, DynamoDB access)
# Example: S3 read access
aws iam attach-role-policy \
  --role-name ecsTaskRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess
```

### 4. CloudWatch Logs Setup

```bash
# Create log group
aws logs create-log-group \
  --log-group-name /ecs/resortslite \
  --region us-east-1

# Set retention policy (optional, 7 days)
aws logs put-retention-policy \
  --log-group-name /ecs/resortslite \
  --retention-in-days 7 \
  --region us-east-1
```

---

## ECS Task Definition Explained

The task definition (`ecs/task-definition.json`) defines how your container should run in ECS Fargate.

### Key Components

#### 1. Launch Type Configuration
```json
{
  "requiresCompatibilities": ["FARGATE"],
  "networkMode": "awsvpc"
}
```
- **FARGATE**: Serverless compute engine for containers
- **awsvpc**: Each task gets its own elastic network interface (ENI)

#### 2. CPU and Memory
```json
{
  "cpu": "512",
  "memory": "1024"
}
```

**Valid Fargate CPU/Memory Combinations:**
| CPU (vCPU) | Memory (MB) |
|------------|-------------|
| 256 (.25)  | 512, 1024, 2048 |
| 512 (.5)   | 1024, 2048, 3072, 4096 |
| 1024 (1)   | 2048-8192 (increments of 1024) |
| 2048 (2)   | 4096-16384 (increments of 1024) |
| 4096 (4)   | 8192-30720 (increments of 1024) |

**Recommendation for ResortsLite:**
- **Development**: cpu: "512", memory: "1024"
- **Production**: cpu: "1024", memory: "2048"

#### 3. IAM Roles
```json
{
  "executionRoleArn": "arn:aws:iam::{{ACCOUNT_ID}}:role/ecsTaskExecutionRole",
  "taskRoleArn": "arn:aws:iam::{{ACCOUNT_ID}}:role/ecsTaskRole"
}
```
- **executionRoleArn**: Required for ECS to pull images and write logs
- **taskRoleArn**: Optional, grants permissions to the application

#### 4. Container Definition
```json
{
  "containerDefinitions": [
    {
      "name": "resortslite",
      "image": "{{IMAGE_URI}}",
      "essential": true,
      "portMappings": [
        {
          "containerPort": 8080,
          "protocol": "tcp"
        }
      ],
      "environment": [...],
      "logConfiguration": {...}
    }
  ]
}
```

#### 5. Environment Variables
All application configuration is externalized via environment variables:
- **Database**: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
- **JWT**: `JWT_SECRET`, `JWT_EXPIRATION`
- **Memcached**: `MEMCACHED_ENDPOINT`, `MEMCACHED_EXPIRATION`
- **External Services**: `APP_PAYMENT_ENDPOINT`, `APP_INVENTORY_ENDPOINT`, `APP_NOTIFICATION_ENDPOINT`

#### 6. Logging Configuration
```json
{
  "logConfiguration": {
    "logDriver": "awslogs",
    "options": {
      "awslogs-group": "/ecs/resortslite",
      "awslogs-region": "{{AWS_REGION}}",
      "awslogs-stream-prefix": "ecs"
    }
  }
}
```

---

## ECS Service Configuration

The service definition (`ecs/service-definition.json`) manages the deployment and scaling of your tasks.

### Key Components

#### 1. Service Configuration
```json
{
  "serviceName": "resortslite-service",
  "cluster": "{{CLUSTER_NAME}}",
  "taskDefinition": "resortslite-task",
  "desiredCount": 2,
  "launchType": "FARGATE"
}
```
- **desiredCount**: Number of task instances to run (2 for high availability)

#### 2. Network Configuration
```json
{
  "networkConfiguration": {
    "awsvpcConfiguration": {
      "subnets": ["{{SUBNET_1}}", "{{SUBNET_2}}"],
      "securityGroups": ["{{SECURITY_GROUP}}"],
      "assignPublicIp": "ENABLED"
    }
  }
}
```
- **subnets**: At least 2 subnets in different availability zones
- **assignPublicIp**: Required if tasks need internet access

#### 3. Deployment Configuration
```json
{
  "deploymentConfiguration": {
    "maximumPercent": 200,
    "minimumHealthyPercent": 50,
    "deploymentCircuitBreaker": {
      "enable": true,
      "rollback": true
    }
  }
}
```
- **maximumPercent**: Maximum tasks during deployment (200% = 2x desired count)
- **minimumHealthyPercent**: Minimum healthy tasks during deployment (50%)
- **deploymentCircuitBreaker**: Automatic rollback on deployment failure

#### 4. Load Balancer Integration
```json
{
  "loadBalancers": [
    {
      "targetGroupArn": "{{TARGET_GROUP_ARN}}",
      "containerName": "resortslite",
      "containerPort": 8080
    }
  ],
  "healthCheckGracePeriodSeconds": 300
}
```
- **healthCheckGracePeriodSeconds**: Time to wait before health checks start (5 minutes for JVM startup)

---

## Deployment to AWS ECS Fargate

### Automated Deployment (Recommended)

#### Linux/macOS
```bash
cd scripts
chmod +x deploy-image.sh
./deploy-image.sh
```

#### Windows
```cmd
cd scripts
deploy-image.bat
```

The deployment script will:
1. Prompt for AWS region and cluster name
2. Create ECS cluster (if not exists)
3. Prompt for network configuration (VPC, subnets, security group)
4. Prompt for Docker image URI
5. Prompt for database and service configuration
6. Optionally create Application Load Balancer
7. Create CloudWatch log group
8. Register ECS task definition
9. Create or update ECS service
10. Wait for service to stabilize
11. Display deployment summary

### Manual Deployment

#### Step 1: Create ECS Cluster
```bash
aws ecs create-cluster \
  --cluster-name resortslite-cluster \
  --region us-east-1
```

#### Step 2: Register Task Definition
```bash
# Replace placeholders in task-definition.json
# Then register
aws ecs register-task-definition \
  --cli-input-json file://ecs/task-definition.json \
  --region us-east-1
```

#### Step 3: Create ECS Service
```bash
# Replace placeholders in service-definition.json
# Then create service
aws ecs create-service \
  --cli-input-json file://ecs/service-definition.json \
  --region us-east-1
```

#### Step 4: Verify Deployment
```bash
# Check service status
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1

# List running tasks
aws ecs list-tasks \
  --cluster resortslite-cluster \
  --service-name resortslite-service \
  --region us-east-1
```

---

## Configuration Management

### Environment Variables

All configuration is managed via environment variables in the ECS task definition.

#### Database Configuration
```bash
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@db-host:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=SecurePassword123!
```

**Best Practice**: Store sensitive values in AWS Secrets Manager and reference them in the task definition:
```json
{
  "secrets": [
    {
      "name": "SPRING_DATASOURCE_PASSWORD",
      "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789:secret:db-password"
    }
  ]
}
```

#### JWT Configuration
```bash
JWT_SECRET=your-256-bit-secret-key-here
JWT_EXPIRATION=3600000
```

#### Memcached Configuration
```bash
MEMCACHED_ENDPOINT=my-cluster.abc123.cfg.use1.cache.amazonaws.com:11211
MEMCACHED_EXPIRATION=3600
```

### AWS Secrets Manager Integration

#### Store Secret
```bash
aws secretsmanager create-secret \
  --name resortslite/db-password \
  --secret-string "YourSecurePassword" \
  --region us-east-1
```

#### Reference in Task Definition
```json
{
  "secrets": [
    {
      "name": "SPRING_DATASOURCE_PASSWORD",
      "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789:secret:resortslite/db-password"
    }
  ]
}
```

#### Update IAM Task Execution Role
```bash
# Add Secrets Manager permissions
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite
```

---

## Monitoring and Logging

### CloudWatch Logs

#### View Logs
```bash
# Tail logs in real-time
aws logs tail /ecs/resortslite --follow --region us-east-1

# View logs for specific time range
aws logs filter-log-events \
  --log-group-name /ecs/resortslite \
  --start-time $(date -d '1 hour ago' +%s)000 \
  --region us-east-1
```

#### Log Insights Queries
```sql
-- Find errors
fields @timestamp, @message
| filter @message like /ERROR/
| sort @timestamp desc
| limit 100

-- Application startup time
fields @timestamp, @message
| filter @message like /Started ResortsLiteApplication/
| sort @timestamp desc

-- Health check failures
fields @timestamp, @message
| filter @message like /health/
| sort @timestamp desc
```

### Spring Boot Actuator Endpoints

#### Health Check
```bash
curl http://<alb-dns>/actuator/health
```

Response:
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP"
    },
    "diskSpace": {
      "status": "UP"
    }
  }
}
```

#### Application Info
```bash
curl http://<alb-dns>/actuator/info
```

### CloudWatch Metrics

#### Create Custom Metrics Dashboard
1. Go to CloudWatch Console
2. Create Dashboard: "ResortsLite-Monitoring"
3. Add widgets:
   - ECS Service CPU Utilization
   - ECS Service Memory Utilization
   - ECS Service Running Task Count
   - ALB Target Response Time
   - ALB Request Count

#### Set Up Alarms
```bash
# CPU utilization alarm
aws cloudwatch put-metric-alarm \
  --alarm-name resortslite-high-cpu \
  --alarm-description "Alert when CPU exceeds 80%" \
  --metric-name CPUUtilization \
  --namespace AWS/ECS \
  --statistic Average \
  --period 300 \
  --threshold 80 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 2 \
  --dimensions Name=ServiceName,Value=resortslite-service Name=ClusterName,Value=resortslite-cluster

# Memory utilization alarm
aws cloudwatch put-metric-alarm \
  --alarm-name resortslite-high-memory \
  --alarm-description "Alert when memory exceeds 80%" \
  --metric-name MemoryUtilization \
  --namespace AWS/ECS \
  --statistic Average \
  --period 300 \
  --threshold 80 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 2 \
  --dimensions Name=ServiceName,Value=resortslite-service Name=ClusterName,Value=resortslite-cluster
```

---

## Troubleshooting

### Common Issues and Solutions

#### 1. Task Fails to Start

**Symptoms**: Tasks transition from PENDING to STOPPED immediately

**Possible Causes**:
- Invalid Docker image URI
- Insufficient IAM permissions
- Invalid CPU/memory combination
- Network configuration issues

**Solutions**:
```bash
# Check task stopped reason
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks <task-id> \
  --region us-east-1 \
  --query 'tasks[0].stoppedReason'

# Check CloudWatch logs for errors
aws logs tail /ecs/resortslite --follow --region us-east-1

# Verify IAM role permissions
aws iam get-role --role-name ecsTaskExecutionRole
```

#### 2. Health Check Failures

**Symptoms**: Tasks fail health checks and are replaced continuously

**Possible Causes**:
- Application not starting properly
- Health endpoint not accessible
- Insufficient startup time

**Solutions**:
```bash
# Increase health check grace period
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --health-check-grace-period-seconds 300 \
  --region us-east-1

# Check application logs
aws logs tail /ecs/resortslite --follow --region us-east-1

# Test health endpoint directly
aws ecs execute-command \
  --cluster resortslite-cluster \
  --task <task-id> \
  --container resortslite \
  --interactive \
  --command "/bin/sh"
```

#### 3. Database Connection Errors

**Symptoms**: Application logs show database connection failures

**Possible Causes**:
- Incorrect database credentials
- Database not accessible from ECS tasks
- Security group rules blocking traffic

**Solutions**:
```bash
# Verify database security group allows inbound from ECS security group
aws ec2 describe-security-groups \
  --group-ids <db-security-group-id> \
  --region us-east-1

# Test database connectivity from ECS task
aws ecs execute-command \
  --cluster resortslite-cluster \
  --task <task-id> \
  --container resortslite \
  --interactive \
  --command "/bin/sh"

# Inside container
nc -zv <db-host> 1521
```

#### 4. Out of Memory Errors

**Symptoms**: Tasks crash with OOMKilled status

**Solutions**:
```bash
# Increase task memory
# Edit task-definition.json and increase memory to 2048
aws ecs register-task-definition \
  --cli-input-json file://ecs/task-definition.json \
  --region us-east-1

# Update service with new task definition
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --task-definition resortslite-task:2 \
  --region us-east-1

# Adjust JVM heap size in task definition
# Set JAVA_OPTS: "-Xmx1536m -Xms768m"
```

#### 5. Service Not Accessible via Load Balancer

**Symptoms**: ALB returns 503 Service Unavailable

**Possible Causes**:
- Target group health checks failing
- Security group not allowing traffic
- Tasks not registered with target group

**Solutions**:
```bash
# Check target group health
aws elbv2 describe-target-health \
  --target-group-arn <target-group-arn> \
  --region us-east-1

# Verify security group allows traffic from ALB
aws ec2 describe-security-groups \
  --group-ids <ecs-security-group-id> \
  --region us-east-1

# Check ALB listener rules
aws elbv2 describe-listeners \
  --load-balancer-arn <alb-arn> \
  --region us-east-1
```

### Debugging Commands

```bash
# View service events
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1 \
  --query 'services[0].events[0:10]'

# List all tasks
aws ecs list-tasks \
  --cluster resortslite-cluster \
  --service-name resortslite-service \
  --region us-east-1

# Describe specific task
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks <task-id> \
  --region us-east-1

# View task logs
aws logs get-log-events \
  --log-group-name /ecs/resortslite \
  --log-stream-name ecs/resortslite/<task-id> \
  --region us-east-1
```

---

## Security Best Practices

### 1. Use AWS Secrets Manager
Store sensitive configuration in Secrets Manager instead of environment variables:
- Database passwords
- JWT secrets
- API keys
- Third-party service credentials

### 2. Enable Container Image Scanning
```bash
# Enable image scanning on ECR repository
aws ecr put-image-scanning-configuration \
  --repository-name resortslite \
  --image-scanning-configuration scanOnPush=true \
  --region us-east-1
```

### 3. Use Private Subnets with NAT Gateway
For production, deploy ECS tasks in private subnets:
```bash
# Create private subnets
PRIVATE_SUBNET_1=$(aws ec2 create-subnet \
  --vpc-id $VPC_ID \
  --cidr-block 10.0.10.0/24 \
  --availability-zone us-east-1a \
  --query 'Subnet.SubnetId' \
  --output text)

# Create NAT Gateway in public subnet
# Update route table for private subnets to use NAT Gateway
```

### 4. Implement Least Privilege IAM Policies
Create custom IAM policies with minimal required permissions:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject"
      ],
      "Resource": "arn:aws:s3:::resortslite-bucket/*"
    }
  ]
}
```

### 5. Enable VPC Flow Logs
```bash
aws ec2 create-flow-logs \
  --resource-type VPC \
  --resource-ids $VPC_ID \
  --traffic-type ALL \
  --log-destination-type cloud-watch-logs \
  --log-group-name /aws/vpc/resortslite \
  --region us-east-1
```

### 6. Use HTTPS with ACM Certificates
```bash
# Request certificate
aws acm request-certificate \
  --domain-name resortslite.example.com \
  --validation-method DNS \
  --region us-east-1

# Update ALB listener to use HTTPS
aws elbv2 create-listener \
  --load-balancer-arn <alb-arn> \
  --protocol HTTPS \
  --port 443 \
  --certificates CertificateArn=<certificate-arn> \
  --default-actions Type=forward,TargetGroupArn=<target-group-arn> \
  --region us-east-1
```

### 7. Enable AWS WAF
```bash
# Create WAF web ACL
aws wafv2 create-web-acl \
  --name resortslite-waf \
  --scope REGIONAL \
  --default-action Allow={} \
  --region us-east-1

# Associate with ALB
aws wafv2 associate-web-acl \
  --web-acl-arn <web-acl-arn> \
  --resource-arn <alb-arn> \
  --region us-east-1
```

---

## Scaling and Performance

### Auto Scaling Configuration

#### Target Tracking Scaling (Recommended)
```bash
# Register scalable target
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --scalable-dimension ecs:service:DesiredCount \
  --resource-id service/resortslite-cluster/resortslite-service \
  --min-capacity 2 \
  --max-capacity 10 \
  --region us-east-1

# Create scaling policy based on CPU utilization
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --scalable-dimension ecs:service:DesiredCount \
  --resource-id service/resortslite-cluster/resortslite-service \
  --policy-name cpu-scaling-policy \
  --policy-type TargetTrackingScaling \
  --target-tracking-scaling-policy-configuration file://scaling-policy.json \
  --region us-east-1
```

**scaling-policy.json**:
```json
{
  "TargetValue": 70.0,
  "PredefinedMetricSpecification": {
    "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
  },
  "ScaleInCooldown": 300,
  "ScaleOutCooldown": 60
}
```

#### Step Scaling
```bash
# Create CloudWatch alarm for high CPU
aws cloudwatch put-metric-alarm \
  --alarm-name resortslite-cpu-high \
  --metric-name CPUUtilization \
  --namespace AWS/ECS \
  --statistic Average \
  --period 60 \
  --threshold 80 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 2 \
  --dimensions Name=ServiceName,Value=resortslite-service Name=ClusterName,Value=resortslite-cluster

# Create step scaling policy
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --scalable-dimension ecs:service:DesiredCount \
  --resource-id service/resortslite-cluster/resortslite-service \
  --policy-name step-scaling-policy \
  --policy-type StepScaling \
  --step-scaling-policy-configuration file://step-scaling-policy.json \
  --region us-east-1
```

### Performance Tuning

#### JVM Tuning
Optimize JVM settings in task definition:
```json
{
  "environment": [
    {
      "name": "JAVA_OPTS",
      "value": "-Xmx1536m -Xms768m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1 -XX:InitiatingHeapOccupancyPercent=45"
    }
  ]
}
```

#### Database Connection Pooling
Configure HikariCP in `application.properties`:
```properties
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000
spring.datasource.hikari.idle-timeout=600000
spring.datasource.hikari.max-lifetime=1800000
```

#### Memcached Optimization
```properties
memcached.expiration=3600
memcached.pool-size=10
```

### Blue/Green Deployment

```bash
# Create new task definition revision
aws ecs register-task-definition \
  --cli-input-json file://ecs/task-definition-v2.json \
  --region us-east-1

# Update service with new task definition
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --task-definition resortslite-task:2 \
  --deployment-configuration "maximumPercent=200,minimumHealthyPercent=100" \
  --region us-east-1

# Monitor deployment
aws ecs wait services-stable \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1
```

---

## Additional Resources

### AWS Documentation
- [ECS Fargate Documentation](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate.html)
- [ECS Task Definitions](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definitions.html)
- [ECS Service Auto Scaling](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service-auto-scaling.html)

### Spring Boot Resources
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Spring Boot Docker](https://spring.io/guides/gs/spring-boot-docker/)
- [Spring Boot Production Ready](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.endpoints)

### Monitoring and Observability
- [CloudWatch Container Insights](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/ContainerInsights.html)
- [AWS X-Ray](https://aws.amazon.com/xray/)
- [Prometheus + Grafana on ECS](https://aws.amazon.com/blogs/containers/monitoring-amazon-ecs-with-prometheus-and-grafana/)

---

## Support and Maintenance

### Regular Maintenance Tasks
1. **Update Docker images** with security patches
2. **Review CloudWatch logs** for errors and warnings
3. **Monitor resource utilization** and adjust task size
4. **Update task definitions** with new configuration
5. **Review IAM policies** and remove unused permissions
6. **Rotate secrets** in AWS Secrets Manager
7. **Update Spring Boot** to latest patch version

### Backup and Disaster Recovery
1. **Database backups**: Configure automated RDS snapshots
2. **Configuration backups**: Store task definitions in version control
3. **Disaster recovery plan**: Document recovery procedures
4. **Multi-region deployment**: Consider deploying to multiple regions

---

## Conclusion

This guide provides comprehensive instructions for deploying ResortsLite to AWS ECS Fargate. Follow the best practices outlined here to ensure a secure, scalable, and maintainable deployment.

For questions or issues, refer to the troubleshooting section or consult AWS documentation.

**Happy Deploying! 🚀**
