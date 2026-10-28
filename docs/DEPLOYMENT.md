# ResortsLite — Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Building and Pushing the Docker Image](#building-and-pushing-the-docker-image)
6. [AWS EKS Deployment](#aws-eks-deployment)
7. [Configuration Management](#configuration-management)
8. [Monitoring and Health Checks](#monitoring-and-health-checks)
9. [Scaling and Rolling Updates](#scaling-and-rolling-updates)
10. [Troubleshooting](#troubleshooting)
11. [Security Considerations](#security-considerations)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.x  
**Java Version**: Java 8  
**Build Tool**: Maven  
**Target Platform**: AWS EKS (Elastic Kubernetes Service)  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

ResortsLite is a legacy resort booking application modernized for cloud-native deployment on AWS EKS. It uses Amazon ElastiCache (Redis) for distributed session storage and caching, and Oracle as the primary database.

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker | 20.10+ | Container runtime |
| Docker Compose | 2.x | Local orchestration |
| Java JDK | 8+ | Local builds (optional) |
| Maven | 3.8+ | Local builds (optional) |

### AWS EKS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS authentication and ECR |
| kubectl | 1.27+ | Kubernetes cluster management |
| eksctl | 0.150+ | EKS cluster creation (optional) |

### AWS IAM Permissions Required
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ecr:GetAuthorizationToken",
        "ecr:BatchCheckLayerAvailability",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchGetImage",
        "ecr:PutImage",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload",
        "ecr:CreateRepository",
        "ecr:DescribeRepositories",
        "eks:DescribeCluster",
        "eks:ListClusters"
      ],
      "Resource": "*"
    }
  ]
}
```

---

## Project Structure

```
TEST/
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
│       │   ├── config/
│       │   │   └── RedisSessionConfig.java
│       │   └── entity/
│       │       └── Doctor.java
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
# Database
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=your_db_password

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=

# Service endpoints
PAYMENT_API_URL=http://payment-svc:9090/payments/charge

# Reports
REPORT_BASE_PATH=/var/app/reports/
```

### 2. Start the Application

```bash
# Build and start
docker-compose up --build

# Start in background
docker-compose up -d --build

# View logs
docker-compose logs -f resortslite

# Stop
docker-compose down
```

### 3. Verify the Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=STANDARD&checkIn=2024-01-15&checkOut=2024-01-20"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=DELUXE"
```

---

## Building and Pushing the Docker Image

### Linux / macOS

```bash
# Make the script executable
chmod +x scripts/build-push.sh

# Run from project root
./scripts/build-push.sh
```

The script will prompt you to:
1. Enter an image tag (default: `latest`)
2. Select registry type (AWS ECR or Docker Hub)
3. Provide registry credentials

### Windows

```cmd
scripts\build-push.bat
```

### Manual Docker Build

```bash
# Build
docker build -t resortslite:latest .

# Tag for ECR
docker tag resortslite:latest 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest

# Authenticate with ECR
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin 123456789012.dkr.ecr.us-east-1.amazonaws.com

# Push
docker push 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
```

---

## AWS EKS Deployment

### Step 1: Set Up AWS CLI

```bash
aws configure
# Enter: AWS Access Key ID, Secret Access Key, Region, Output format
```

### Step 2: Create or Connect to EKS Cluster

**Create a new cluster (if needed):**
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

**Connect to existing cluster:**
```bash
aws eks update-kubeconfig --region us-east-1 --name resortslite-cluster
kubectl cluster-info
```

### Step 3: Install AWS Load Balancer Controller

The ingress manifest uses the AWS Load Balancer Controller. Install it:

```bash
# Add the EKS chart repo
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install the controller
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=resortslite-cluster \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Step 4: Deploy the Application

**Linux / macOS:**
```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

**Windows:**
```cmd
scripts\deploy-image.bat
```

The script will prompt for:
- AWS region and EKS cluster name
- Full Docker image URI
- Database connection details
- Redis/ElastiCache connection details
- Service endpoint URLs

### Step 5: Verify Deployment

```bash
# Check all resources
kubectl get all -n resortslite

# Check pod status
kubectl get pods -n resortslite

# Check pod logs
kubectl logs -l app=resortslite -n resortslite --tail=100

# Check ingress
kubectl get ingress -n resortslite

# Describe deployment
kubectl describe deployment resortslite -n resortslite
```

### Step 6: Access the Application

```bash
# Get the ALB DNS name
kubectl get ingress resortslite-ingress -n resortslite \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
```

Access the application at: `http://<ALB_DNS_NAME>/api/bookings/availability?roomType=STANDARD`

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` |
| `SPRING_DATASOURCE_URL` | Oracle JDBC URL | — |
| `SPRING_DATASOURCE_USERNAME` | DB username | — |
| `SPRING_DATASOURCE_PASSWORD` | DB password | — |
| `REDIS_HOST` | ElastiCache endpoint | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis auth token | _(empty)_ |
| `PAYMENT_API_URL` | Payment service URL | `http://payment-svc:9090/payments/charge` |
| `REPORT_BASE_PATH` | Report file directory | `/var/app/reports/` |
| `JAVA_OPTS` | JVM options | `-Xms256m -Xmx512m ...` |

### Using AWS Secrets Manager (Recommended)

For production, store sensitive values in AWS Secrets Manager and use the AWS Secrets Manager CSI driver:

```bash
# Create a secret
aws secretsmanager create-secret \
  --name resortslite/db-credentials \
  --secret-string '{"username":"admin","password":"your_password"}'
```

### Using Kubernetes Secrets

```bash
# Create a secret for database credentials
kubectl create secret generic resortslite-db-secret \
  --from-literal=username=admin \
  --from-literal=password=your_password \
  -n resortslite

# Create a secret for Redis
kubectl create secret generic resortslite-redis-secret \
  --from-literal=password=your_redis_password \
  -n resortslite
```

---

## Monitoring and Health Checks

### Spring Boot Actuator Endpoints

| Endpoint | URL | Purpose |
|----------|-----|---------|
| Health | `GET /actuator/health` | Liveness & readiness probe |

### Kubernetes Health Probes

The deployment is configured with:
- **Liveness Probe**: `GET /actuator/health` — restarts container if unhealthy
  - Initial delay: 60s (JVM startup time)
  - Period: 30s
- **Readiness Probe**: `GET /actuator/health` — removes from load balancer if not ready
  - Initial delay: 30s
  - Period: 15s

### Viewing Metrics

```bash
# Pod resource usage
kubectl top pods -n resortslite

# Node resource usage
kubectl top nodes
```

---

## Scaling and Rolling Updates

### Manual Scaling

```bash
# Scale to 3 replicas
kubectl scale deployment resortslite --replicas=3 -n resortslite

# Verify
kubectl get pods -n resortslite
```

### Horizontal Pod Autoscaler (HPA)

```bash
# Create HPA (scale between 2-10 pods at 70% CPU)
kubectl autoscale deployment resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortslite

# Check HPA status
kubectl get hpa -n resortslite
```

### Rolling Updates

```bash
# Update the image
kubectl set image deployment/resortslite \
  resortslite=123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:v2.0.0 \
  -n resortslite

# Monitor rollout
kubectl rollout status deployment/resortslite -n resortslite

# Rollback if needed
kubectl rollout undo deployment/resortslite -n resortslite

# View rollout history
kubectl rollout history deployment/resortslite -n resortslite
```

---

## Troubleshooting

### Pod Not Starting

```bash
# Check pod events
kubectl describe pod <pod-name> -n resortslite

# Check pod logs
kubectl logs <pod-name> -n resortslite

# Check previous container logs (if crashed)
kubectl logs <pod-name> -n resortslite --previous
```

### Common Issues

**Issue: `ImagePullBackOff`**
```bash
# Verify ECR credentials
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin <account>.dkr.ecr.us-east-1.amazonaws.com

# Check image exists
aws ecr describe-images --repository-name resortslite --region us-east-1
```

**Issue: `CrashLoopBackOff` — Database connection failure**
```bash
# Check environment variables in pod
kubectl exec -it <pod-name> -n resortslite -- env | grep SPRING_DATASOURCE

# Verify Oracle DB is reachable from the cluster
kubectl run -it --rm debug --image=busybox --restart=Never -- \
  nc -zv <db-host> 1521
```

**Issue: Redis connection failure**
```bash
# Check Redis env vars
kubectl exec -it <pod-name> -n resortslite -- env | grep REDIS

# Test Redis connectivity
kubectl run -it --rm redis-test --image=redis:alpine --restart=Never -- \
  redis-cli -h <redis-host> -p 6379 ping
```

**Issue: Ingress not getting an external IP**
```bash
# Check AWS Load Balancer Controller logs
kubectl logs -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller

# Verify ingress annotations
kubectl describe ingress resortslite-ingress -n resortslite
```

**Issue: Health check failing (pod not ready)**
```bash
# Test health endpoint directly
kubectl port-forward <pod-name> 8080:8080 -n resortslite
curl http://localhost:8080/actuator/health
```

### JVM Memory Issues

If pods are OOMKilled, increase memory limits in `kubernetes/deployment.yaml`:
```yaml
resources:
  requests:
    memory: "768Mi"
  limits:
    memory: "1536Mi"
```

And update JAVA_OPTS accordingly:
```yaml
- name: JAVA_OPTS
  value: "-Xms512m -Xmx1024m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
```

---

## Security Considerations

1. **Never commit secrets**: Use `.env` files locally (add to `.gitignore`) and Kubernetes Secrets or AWS Secrets Manager in production.

2. **Non-root container**: The Dockerfile creates and uses a non-root `appuser` account.

3. **Network policies**: Consider adding Kubernetes NetworkPolicies to restrict pod-to-pod communication.

4. **Image scanning**: Enable ECR image scanning to detect vulnerabilities:
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name resortslite \
     --image-scanning-configuration scanOnPush=true \
     --region us-east-1
   ```

5. **IRSA (IAM Roles for Service Accounts)**: Use IRSA to grant the application pod access to AWS services without static credentials.

6. **TLS/HTTPS**: Update the ingress to use HTTPS with ACM certificates:
   ```yaml
   annotations:
     alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS": 443}]'
     alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:us-east-1:123456789012:certificate/xxx
   ```

7. **Dependency vulnerabilities**: The application currently includes vulnerable dependencies (log4j 2.14.1, commons-collections 3.2.1). These should be upgraded before production deployment.

---

## Java-Specific Notes

- **JVM Startup**: Spring Boot applications on Java 8 may take 30–60 seconds to start. The liveness probe has a 60-second initial delay to accommodate this.
- **Container Support**: `-XX:+UseContainerSupport` ensures the JVM respects container memory limits rather than using host memory.
- **MaxRAMPercentage**: Set to 75% to leave headroom for non-heap memory (Metaspace, thread stacks, etc.).
- **Graceful Shutdown**: The deployment has `terminationGracePeriodSeconds: 30` to allow in-flight requests to complete.
- **Session Externalization**: HTTP sessions are stored in Redis (ElastiCache) via Spring Session, enabling safe horizontal scaling.
