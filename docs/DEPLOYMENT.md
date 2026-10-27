# ResortsLite — Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Building and Pushing the Docker Image](#building-and-pushing-the-docker-image)
6. [AWS EKS Deployment](#aws-eks-deployment)
7. [Configuration Management](#configuration-management)
8. [Kubernetes Resource Reference](#kubernetes-resource-reference)
9. [Scaling and Management](#scaling-and-management)
10. [Troubleshooting](#troubleshooting)
11. [Security Considerations](#security-considerations)
12. [Java-Specific Notes](#java-specific-notes)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.x  
**Java Version**: 8  
**Build Tool**: Maven  
**Runtime Base Image**: `amazoncorretto:8`  
**Target Platform**: AWS EKS (Elastic Kubernetes Service)  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

ResortsLite is a legacy resort booking application modernized for cloud-native deployment on AWS EKS. It uses Amazon ElastiCache (Redis) for distributed session management and caching, and Oracle Database for persistent storage.

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker Desktop | 24.x+ | Container build and local run |
| Docker Compose | 2.x+ | Local multi-container orchestration |
| Java JDK | 8+ | Local development (optional) |
| Maven | 3.8+ | Local build (optional) |

### AWS EKS Deployment
| Tool | Version | Install |
|------|---------|---------|
| AWS CLI | 2.x | `brew install awscli` / [AWS docs](https://docs.aws.amazon.com/cli/latest/userguide/install-cliv2.html) |
| kubectl | 1.27+ | `brew install kubectl` / [K8s docs](https://kubernetes.io/docs/tasks/tools/) |
| eksctl | 0.160+ | `brew install eksctl` / [eksctl docs](https://eksctl.io/installation/) |

### AWS IAM Permissions Required
```
ecr:GetAuthorizationToken
ecr:BatchCheckLayerAvailability
ecr:GetDownloadUrlForLayer
ecr:BatchGetImage
ecr:CreateRepository
ecr:DescribeRepositories
ecr:PutImage
eks:DescribeCluster
eks:ListClusters
sts:GetCallerIdentity
```

---

## Project Structure

```
.
├── Dockerfile                    # Multi-stage Docker build
├── docker-compose.yml            # Local development compose file
├── .dockerignore                 # Docker build exclusions
├── pom.xml                       # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   ├── ReportService.java
│       │   ├── RedisConfig.java
│       │   └── entity/Doctor.java
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml            # Kubernetes namespace
│   ├── deployment.yaml           # Application deployment
│   ├── service.yaml              # ClusterIP service
│   └── ingress.yaml              # AWS ALB ingress
├── scripts/
│   ├── build-push.sh             # Linux/macOS build & push
│   ├── build-push.bat            # Windows build & push
│   ├── deploy-image.sh           # Linux/macOS EKS deploy
│   └── deploy-image.bat          # Windows EKS deploy
└── docs/
    └── DEPLOYMENT.md             # This file
```

---

## Local Development with Docker Compose

### 1. Configure Environment Variables

Create a `.env` file in the project root (never commit this file):

```bash
# Oracle Database
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@<your-db-host>:1521:<SID>
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=your-secure-password

# Redis / ElastiCache (use localhost for local Redis)
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_SSL=false

# Internal service endpoints
PAYMENT_API_URL=http://payment-service:9090/payments/charge
APP_INVENTORY_ENDPOINT=http://inventory-service:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://notify-service:7070/send
```

### 2. Build and Start

```bash
# Build and start the application
docker compose up --build

# Run in background
docker compose up --build -d

# View logs
docker compose logs -f resortslite

# Stop
docker compose down
```

### 3. Verify Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=STANDARD&checkIn=2024-01-15&checkOut=2024-01-20"
```

---

## Building and Pushing the Docker Image

### Linux / macOS

```bash
# Make script executable
chmod +x scripts/build-push.sh

# Run from project root
./scripts/build-push.sh
```

The script will prompt for:
1. Image tag (default: `latest`)
2. Registry type: `1) AWS ECR` or `2) Docker Hub`
3. Registry-specific credentials

### Windows

```cmd
scripts\build-push.bat
```

### Manual Docker Build

```bash
# Build image
docker build -t resortslite:latest .

# Tag for ECR
docker tag resortslite:latest <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/resortslite:latest

# Push to ECR
aws ecr get-login-password --region <REGION> | \
  docker login --username AWS --password-stdin <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com
docker push <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/resortslite:latest
```

---

## AWS EKS Deployment

### Step 1: Create EKS Cluster (if not existing)

```bash
eksctl create cluster \
  --name resortslite-cluster \
  --region us-east-1 \
  --nodegroup-name standard-workers \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 4 \
  --managed
```

### Step 2: Install AWS Load Balancer Controller

The ingress uses the AWS Load Balancer Controller for ALB provisioning:

```bash
# Add Helm repo
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install controller
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=resortslite-cluster \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Step 3: Create Kubernetes Secrets

Before deploying, create secrets for sensitive values:

```bash
# Database credentials
kubectl create secret generic resortslite-db-secret \
  --namespace resortslite \
  --from-literal=username=<DB_USERNAME> \
  --from-literal=password=<DB_PASSWORD>

# Redis password (if applicable)
kubectl create secret generic resortslite-redis-secret \
  --namespace resortslite \
  --from-literal=password=<REDIS_PASSWORD>
```

> **Note**: Create the namespace first before creating secrets:
> ```bash
> kubectl apply -f kubernetes/namespace.yaml
> ```

### Step 4: Run Deployment Script

```bash
# Linux/macOS
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh

# Windows
scripts\deploy-image.bat
```

The script will prompt for:
- AWS region
- EKS cluster name
- Full Docker image URI
- Application environment variables (database URL, Redis host, service endpoints)

### Step 5: Verify Deployment

```bash
# Check all resources
kubectl get all -n resortslite

# Check pod status
kubectl get pods -n resortslite

# Check ingress and get ALB hostname
kubectl get ingress -n resortslite

# View application logs
kubectl logs -l app=resortslite -n resortslite --tail=100 -f
```

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Default | Required |
|----------|-------------|---------|----------|
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` | No |
| `SPRING_DATASOURCE_URL` | Oracle JDBC URL | — | **Yes** |
| `SPRING_DATASOURCE_USERNAME` | DB username | — | **Yes** |
| `SPRING_DATASOURCE_PASSWORD` | DB password | — | **Yes** |
| `REDIS_HOST` | Redis/ElastiCache host | `localhost` | **Yes** |
| `REDIS_PORT` | Redis port | `6379` | No |
| `REDIS_PASSWORD` | Redis auth password | `` | No |
| `REDIS_SSL` | Enable Redis TLS | `false` | No |
| `PAYMENT_API_URL` | Payment service URL | `http://payment-service:9090/payments/charge` | No |
| `APP_INVENTORY_ENDPOINT` | Inventory service URL | `http://inventory-service:8081/rooms` | No |
| `APP_NOTIFICATION_ENDPOINT` | Notification service URL | `http://notify-service:7070/send` | No |
| `REPORT_BASE_PATH` | Report file storage path | `/var/reports` | No |
| `JAVA_OPTS` | JVM options | `-Xms256m -Xmx512m ...` | No |

### Using AWS Secrets Manager (Recommended for Production)

```bash
# Store database credentials
aws secretsmanager create-secret \
  --name resortslite/db-credentials \
  --secret-string '{"username":"admin","password":"your-password"}'

# Use External Secrets Operator to sync to Kubernetes secrets
# https://external-secrets.io/
```

---

## Kubernetes Resource Reference

### Namespace
- **File**: `kubernetes/namespace.yaml`
- **Name**: `resortslite`

### Deployment
- **File**: `kubernetes/deployment.yaml`
- **Replicas**: 2
- **Strategy**: RollingUpdate (maxSurge: 1, maxUnavailable: 0)
- **Resources**:
  - Requests: CPU 250m, Memory 512Mi
  - Limits: CPU 500m, Memory 1Gi
- **Liveness Probe**: `GET /actuator/health` (initial delay: 90s)
- **Readiness Probe**: `GET /actuator/health` (initial delay: 60s)

### Service
- **File**: `kubernetes/service.yaml`
- **Type**: ClusterIP
- **Port**: 80 → 8080

### Ingress
- **File**: `kubernetes/ingress.yaml`
- **Class**: AWS ALB (internet-facing)
- **Host**: `resortslite.example.com` (update to your domain)
- **TLS**: SSL redirect enabled

---

## Scaling and Management

### Horizontal Pod Autoscaling

```bash
# Create HPA
kubectl autoscale deployment resortslite \
  --namespace resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10

# Check HPA status
kubectl get hpa -n resortslite
```

### Manual Scaling

```bash
kubectl scale deployment resortslite --replicas=4 -n resortslite
```

### Rolling Update

```bash
# Update image
kubectl set image deployment/resortslite \
  resortslite=<NEW_IMAGE_URI> \
  -n resortslite

# Monitor rollout
kubectl rollout status deployment/resortslite -n resortslite
```

### Rollback

```bash
# Rollback to previous version
kubectl rollout undo deployment/resortslite -n resortslite

# Rollback to specific revision
kubectl rollout history deployment/resortslite -n resortslite
kubectl rollout undo deployment/resortslite --to-revision=2 -n resortslite
```

---

## Troubleshooting

### Pod Not Starting

```bash
# Describe pod for events
kubectl describe pod -l app=resortslite -n resortslite

# Check logs
kubectl logs -l app=resortslite -n resortslite --previous
```

**Common causes**:
- Missing secrets (`resortslite-db-secret`, `resortslite-redis-secret`)
- Incorrect Oracle JDBC URL format
- Redis connection refused (check ElastiCache security group)
- JVM OOM — increase memory limits

### Health Check Failing

```bash
# Port-forward to test locally
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite

# Test health endpoint
curl http://localhost:8080/actuator/health
```

**Common causes**:
- Oracle DB unreachable (check VPC security groups)
- Redis unreachable (check ElastiCache security group allows EKS node IPs)
- JVM startup time exceeds `initialDelaySeconds` — increase probe delay

### Ingress Not Getting ALB Hostname

```bash
# Check AWS Load Balancer Controller logs
kubectl logs -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller

# Verify IAM permissions for ALB controller
aws iam list-attached-role-policies --role-name <EKS_NODE_ROLE>
```

### Redis Connection Issues

```bash
# Verify Redis connectivity from pod
kubectl exec -it <POD_NAME> -n resortslite -- sh
# Inside pod:
# nc -zv <REDIS_HOST> 6379
```

Ensure ElastiCache security group allows inbound on port 6379 from EKS node security group.

### OOMKilled Pods

```bash
kubectl describe pod <POD_NAME> -n resortslite | grep -A5 "OOMKilled"
```

Increase memory limits in `kubernetes/deployment.yaml`:
```yaml
resources:
  limits:
    memory: "2Gi"
```

---

## Security Considerations

1. **Never commit secrets** — Use Kubernetes Secrets, AWS Secrets Manager, or External Secrets Operator
2. **Non-root container** — The Dockerfile creates and uses a non-root `appuser`
3. **Network policies** — Consider adding Kubernetes NetworkPolicy to restrict pod-to-pod traffic
4. **Image scanning** — Enable ECR image scanning on push:
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name resortslite \
     --image-scanning-configuration scanOnPush=true \
     --region us-east-1
   ```
5. **IRSA for ElastiCache** — Use IAM Roles for Service Accounts to grant pods access to ElastiCache without static credentials
6. **TLS** — The ingress is configured with SSL redirect; ensure ACM certificate is provisioned for your domain
7. **Log4j** — The pom.xml references log4j 2.14.1 (CVE-2021-44228). **Upgrade to 2.17.2+** before production deployment

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with:
```
-Xms256m -Xmx512m
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0
```

`UseContainerSupport` ensures the JVM respects container memory limits (available since Java 8u191). `MaxRAMPercentage=75.0` allows the JVM to use up to 75% of the container's memory limit.

### Spring Boot Actuator

Health endpoints are exposed at:
- **Health**: `GET /actuator/health`
- **Info**: `GET /actuator/info`

These are used by Kubernetes liveness and readiness probes.

### Spring Session + Redis

Session data is stored in Amazon ElastiCache (Redis) under the namespace `resortslite:session`. Sessions expire after 30 minutes (`spring.session.timeout=1800`). This enables stateless horizontal scaling — any pod can serve any request.

### Oracle Database

The application uses Oracle JDBC (`ojdbc8`). Ensure:
- The EKS nodes can reach the Oracle DB host on port 1521
- VPC security groups allow the connection
- The JDBC URL format is: `jdbc:oracle:thin:@<host>:<port>:<SID>` or `jdbc:oracle:thin:@//<host>:<port>/<service>`

### Startup Time

Spring Boot with Oracle JDBC and Redis initialization can take 45–90 seconds. The Kubernetes probes are configured with appropriate initial delays:
- Liveness: 90s initial delay
- Readiness: 60s initial delay

Adjust these values if your environment has slower startup times.
