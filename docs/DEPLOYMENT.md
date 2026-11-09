# ResortsLite — Deployment Guide

## Overview

This guide covers building, containerizing, and deploying the **ResortsLite** Spring Boot application (Java 8) to **AWS EKS (Elastic Kubernetes Service)**.

- **Application**: ResortsLite — Legacy resort booking service
- **Framework**: Spring Boot 2.7.x
- **Java Version**: Java 8
- **Build Tool**: Maven
- **Target Platform**: AWS EKS (Kubernetes)
- **Base Image (Runtime)**: `amazoncorretto:8`

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Project Structure](#project-structure)
3. [Local Development with Docker Compose](#local-development-with-docker-compose)
4. [Build and Push Docker Image](#build-and-push-docker-image)
5. [AWS EKS Prerequisites](#aws-eks-prerequisites)
6. [EKS Cluster Setup](#eks-cluster-setup)
7. [Kubernetes Deployment](#kubernetes-deployment)
8. [Configuration Management](#configuration-management)
9. [Scaling and Management](#scaling-and-management)
10. [Troubleshooting](#troubleshooting)
11. [Security Considerations](#security-considerations)

---

## Prerequisites

### Local Development Tools
- **Docker** 20.10+ — [Install Docker](https://docs.docker.com/get-docker/)
- **Docker Compose** v2+ — included with Docker Desktop
- **Java 8 JDK** — for local builds outside Docker
- **Maven 3.8+** — for local builds outside Docker

### AWS & Kubernetes Tools
- **AWS CLI v2** — [Install AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/install-cliv2.html)
- **kubectl** — [Install kubectl](https://kubernetes.io/docs/tasks/tools/)
- **eksctl** (optional, for cluster creation) — [Install eksctl](https://eksctl.io/introduction/#installation)

### AWS IAM Permissions Required
```
eks:DescribeCluster
eks:UpdateKubeconfig
ecr:GetAuthorizationToken
ecr:BatchCheckLayerAvailability
ecr:GetDownloadUrlForLayer
ecr:BatchGetImage
ecr:CreateRepository
ecr:PutImage
ecr:InitiateLayerUpload
ecr:UploadLayerPart
ecr:CompleteLayerUpload
```

---

## Project Structure

```
Comp check containr new/
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
│       │   ├── RedisSessionConfig.java
│       │   ├── ReportService.java
│       │   └── entity/Doctor.java
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml
│   ├── deployment.yaml
│   ├── service.yaml
│   └── ingress.yaml
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
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@your-db-host:1521:ORCL
SPRING_DATASOURCE_USERNAME=your_db_user
SPRING_DATASOURCE_PASSWORD=your_db_password

# Redis / Amazon ElastiCache
REDIS_HOST=your-redis-host
REDIS_PORT=6379

# Internal service endpoints
PAYMENT_API_URL=http://payment-service:9090/payments/charge
APP_PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge
APP_INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://notify.internal:7070/send

# Report storage
REPORT_BASE_PATH=/tmp/reports/
```

### 2. Build and Start the Application

```bash
# Build and start
docker compose up --build

# Start in background
docker compose up --build -d

# View logs
docker compose logs -f resortslite

# Stop
docker compose down
```

### 3. Verify the Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=STANDARD&checkIn=2024-01-01&checkOut=2024-01-05"
```

---

## Build and Push Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

The script will prompt you to:
1. Enter an image tag (default: `latest`)
2. Select registry: **1. AWS ECR** or **2. Docker Hub**
3. Provide registry credentials

### Windows

```cmd
scripts\build-push.bat
```

### Manual Build (AWS ECR)

```bash
# Set variables
AWS_REGION=us-east-1
AWS_ACCOUNT_ID=123456789012
IMAGE_TAG=1.0.0

# Login to ECR
aws ecr get-login-password --region $AWS_REGION | \
  docker login --username AWS --password-stdin \
  ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com

# Create repository (if not exists)
aws ecr create-repository --repository-name resortslite --region $AWS_REGION

# Build and push
docker build -t ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/resortslite:${IMAGE_TAG} .
docker push ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/resortslite:${IMAGE_TAG}
```

---

## AWS EKS Prerequisites

### 1. Configure AWS CLI

```bash
aws configure
# Enter: AWS Access Key ID, Secret Access Key, Region, Output format
```

### 2. Verify AWS Identity

```bash
aws sts get-caller-identity
```

### 3. Install AWS Load Balancer Controller (for Ingress)

The `ingress.yaml` uses the AWS Load Balancer Controller. Install it on your EKS cluster:

```bash
# Add Helm repo
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install controller
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=<your-cluster-name> \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

---

## EKS Cluster Setup

### Create a New EKS Cluster (if needed)

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

### Configure kubectl

```bash
aws eks update-kubeconfig --region us-east-1 --name resortslite-cluster
kubectl cluster-info
kubectl get nodes
```

---

## Kubernetes Deployment

### Automated Deployment (Recommended)

#### Linux / macOS

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

The script will prompt for:
- AWS Region
- EKS Cluster Name
- Docker image URI (full path with tag)
- Application environment variables (Oracle DB, Redis, service endpoints)

#### Windows

```cmd
scripts\deploy-image.bat
```

### Manual Deployment

```bash
# 1. Set your image URI
IMAGE_URI="123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:1.0.0"

# 2. Update deployment.yaml with actual values
sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g" kubernetes/deployment.yaml
sed -i "s|{{SPRING_DATASOURCE_URL}}|jdbc:oracle:thin:@your-host:1521:ORCL|g" kubernetes/deployment.yaml
sed -i "s|{{SPRING_DATASOURCE_USERNAME}}|your_user|g" kubernetes/deployment.yaml
sed -i "s|{{SPRING_DATASOURCE_PASSWORD}}|your_password|g" kubernetes/deployment.yaml
sed -i "s|{{REDIS_HOST}}|your-elasticache-endpoint|g" kubernetes/deployment.yaml
sed -i "s|{{REDIS_PORT}}|6379|g" kubernetes/deployment.yaml
sed -i "s|{{PAYMENT_API_URL}}|http://payment-service:9090/payments/charge|g" kubernetes/deployment.yaml
sed -i "s|{{APP_PAYMENT_ENDPOINT}}|http://payment-svc:9090/charge|g" kubernetes/deployment.yaml
sed -i "s|{{APP_INVENTORY_ENDPOINT}}|http://inventory-svc:8081/rooms|g" kubernetes/deployment.yaml
sed -i "s|{{APP_NOTIFICATION_ENDPOINT}}|http://notify-svc:7070/send|g" kubernetes/deployment.yaml

# 3. Apply manifests in order
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

# 4. Wait for rollout
kubectl rollout status deployment/resortslite -n resortslite

# 5. Verify
kubectl get pods,svc,ingress -n resortslite
```

### Kubernetes Manifest Descriptions

| File | Description |
|------|-------------|
| `namespace.yaml` | Creates the `resortslite` namespace |
| `deployment.yaml` | Deploys 2 replicas with health probes, resource limits, and env vars |
| `service.yaml` | ClusterIP service exposing port 80 → container port 8080 |
| `ingress.yaml` | AWS ALB Ingress with HTTPS redirect and health check configuration |

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` |
| `SERVER_PORT` | Application port | `8080` |
| `SPRING_DATASOURCE_URL` | Oracle JDBC URL | — |
| `SPRING_DATASOURCE_USERNAME` | DB username | — |
| `SPRING_DATASOURCE_PASSWORD` | DB password | — |
| `REDIS_HOST` | Amazon ElastiCache endpoint | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `PAYMENT_API_URL` | Payment service URL | — |
| `APP_PAYMENT_ENDPOINT` | Payment endpoint | — |
| `APP_INVENTORY_ENDPOINT` | Inventory service URL | — |
| `APP_NOTIFICATION_ENDPOINT` | Notification service URL | — |
| `REPORT_BASE_PATH` | Report file storage path | `/tmp/reports/` |
| `JAVA_OPTS` | JVM options | See Dockerfile |

### Using Kubernetes Secrets (Recommended for Production)

```bash
# Create secret for database credentials
kubectl create secret generic resortslite-db-secret \
  --from-literal=url='jdbc:oracle:thin:@host:1521:ORCL' \
  --from-literal=username='admin' \
  --from-literal=password='your-secure-password' \
  -n resortslite

# Create secret for Redis
kubectl create secret generic resortslite-redis-secret \
  --from-literal=host='your-elasticache-endpoint' \
  --from-literal=port='6379' \
  -n resortslite
```

Then reference in `deployment.yaml`:
```yaml
env:
  - name: SPRING_DATASOURCE_PASSWORD
    valueFrom:
      secretKeyRef:
        name: resortslite-db-secret
        key: password
```

---

## Scaling and Management

### Manual Scaling

```bash
# Scale to 3 replicas
kubectl scale deployment resortslite --replicas=3 -n resortslite

# Check status
kubectl get pods -n resortslite
```

### Horizontal Pod Autoscaler (HPA)

```bash
kubectl autoscale deployment resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortslite

kubectl get hpa -n resortslite
```

### Rolling Update

```bash
# Update image
kubectl set image deployment/resortslite \
  resortslite=123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:2.0.0 \
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
# Check pod status
kubectl get pods -n resortslite

# Describe pod for events
kubectl describe pod <pod-name> -n resortslite

# View pod logs
kubectl logs <pod-name> -n resortslite
kubectl logs <pod-name> -n resortslite --previous  # crashed pod logs
```

### Common Issues

#### 1. OOMKilled (Out of Memory)
```bash
# Increase memory limits in deployment.yaml
resources:
  limits:
    memory: "2Gi"   # Increase from 1Gi
```

#### 2. CrashLoopBackOff
```bash
# Check logs for startup errors
kubectl logs <pod-name> -n resortslite

# Common causes:
# - Cannot connect to Oracle DB (check SPRING_DATASOURCE_URL)
# - Cannot connect to Redis (check REDIS_HOST/REDIS_PORT)
# - Missing environment variables
```

#### 3. Health Check Failing
```bash
# Verify health endpoint is accessible
kubectl exec -it <pod-name> -n resortslite -- \
  wget -qO- http://localhost:8080/actuator/health

# Check actuator configuration in application.properties:
# management.endpoints.web.exposure.include=health
# management.endpoint.health.show-details=always
```

#### 4. Ingress Not Getting External IP
```bash
# Check AWS Load Balancer Controller is installed
kubectl get pods -n kube-system | grep aws-load-balancer

# Check ingress events
kubectl describe ingress resortslite-ingress -n resortslite
```

#### 5. ImagePullBackOff
```bash
# Verify ECR image URI is correct
kubectl describe pod <pod-name> -n resortslite

# Ensure EKS node IAM role has ECR pull permissions
# Attach policy: AmazonEC2ContainerRegistryReadOnly
```

### Useful Diagnostic Commands

```bash
# Get all resources in namespace
kubectl get all -n resortslite

# Get events sorted by time
kubectl get events -n resortslite --sort-by='.lastTimestamp'

# Port-forward for local testing
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite

# Execute shell in running pod
kubectl exec -it <pod-name> -n resortslite -- /bin/sh
```

---

## Security Considerations

1. **Secrets Management**: Never store database passwords or API keys in `deployment.yaml`. Use Kubernetes Secrets or AWS Secrets Manager with External Secrets Operator.

2. **Non-Root Container**: The Dockerfile runs the application as a non-root user (`appuser`) for security.

3. **Network Policies**: Consider adding Kubernetes NetworkPolicies to restrict pod-to-pod communication.

4. **Image Scanning**: Enable ECR image scanning to detect vulnerabilities:
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name resortslite \
     --image-scanning-configuration scanOnPush=true \
     --region us-east-1
   ```

5. **IRSA (IAM Roles for Service Accounts)**: Use IRSA for fine-grained AWS permissions instead of node-level IAM roles.

6. **TLS/HTTPS**: The ingress is configured with HTTPS redirect. Ensure your ACM certificate ARN is added to the ingress annotations:
   ```yaml
   alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:us-east-1:123456789012:certificate/xxx
   ```

7. **Dependency Vulnerabilities**: The application currently includes vulnerable dependencies (log4j 2.14.1 with CVE-2021-44228, commons-collections 3.2.1 with CVE-2015-6420). These should be upgraded before production deployment.

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with:
```
-Xmx512m -Xms256m
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0
```

`UseContainerSupport` ensures the JVM respects container memory limits (available in Java 8u191+). Adjust `JAVA_OPTS` in the deployment environment variables based on your workload.

### Spring Boot Actuator

Health checks use `/actuator/health` which is exposed via:
```properties
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=always
management.endpoints.web.base-path=/actuator
```

### Redis Session (Amazon ElastiCache)

The application uses Spring Session backed by Redis for distributed session management. Ensure your Amazon ElastiCache cluster is accessible from the EKS worker nodes (same VPC, correct security group rules).

### Oracle Database

The application uses Oracle JDBC. Ensure:
- Oracle DB is accessible from EKS worker nodes
- Security groups allow traffic on port 1521
- Oracle JDBC driver (`ojdbc8`) is included in the Maven dependencies (it is)
