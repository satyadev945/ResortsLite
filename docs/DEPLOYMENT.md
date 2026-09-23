# ResortsLite — Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Building and Pushing the Docker Image](#building-and-pushing-the-docker-image)
6. [AWS EKS Deployment](#aws-eks-deployment)
7. [Configuration Management](#configuration-management)
8. [Health Checks and Monitoring](#health-checks-and-monitoring)
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

ResortsLite is a legacy resort booking application modernised for cloud-native deployment on AWS EKS. It uses Amazon ElastiCache (Redis) for distributed session management and booking cache, and Oracle as the primary database.

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker | 20.10+ | Container build and run |
| Docker Compose | 2.x | Local multi-container orchestration |
| Java JDK | 8+ | Local build (optional) |
| Maven | 3.8+ | Local build (optional) |

### AWS EKS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS authentication and ECR push |
| kubectl | 1.27+ | Kubernetes cluster management |
| eksctl | 0.150+ | EKS cluster creation (optional) |
| Docker | 20.10+ | Image build and push |

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
│       │   ├── HealthController.java
│       │   ├── ReportService.java
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
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=your_password_here

# Redis / ElastiCache
REDIS_HOST=localhost
REDIS_PORT=6379
BOOKING_CACHE_TTL_SECONDS=3600

# Internal service endpoints
PAYMENT_API_URL=http://payment-service:9090/payments/charge
APP_PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge
APP_INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://notify.internal:7070/send

# File paths
REPORT_BASE_PATH=/var/legacy/reports/
BACKUP_PATH=/var/legacy/backups/
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

# Custom health endpoint
curl http://localhost:8080/health

# Application info
curl http://localhost:8080/actuator/info
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

The script will prompt for:
1. Image tag (default: `latest`)
2. Registry type: `1` for AWS ECR, `2` for Docker Hub
3. Registry-specific credentials and details

### Windows

```cmd
# Run from project root
scripts\build-push.bat
```

### Manual Build (Advanced)

```bash
# Build image
docker build -t resortslite:latest .

# Tag for ECR
docker tag resortslite:latest 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest

# Authenticate with ECR
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin \
  123456789012.dkr.ecr.us-east-1.amazonaws.com

# Push
docker push 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
```

---

## AWS EKS Deployment

### Step 1: Configure AWS CLI

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

**Connect to an existing cluster:**
```bash
aws eks update-kubeconfig --region us-east-1 --name resortslite-cluster
kubectl cluster-info
```

### Step 3: Install AWS Load Balancer Controller

The ingress manifest uses the AWS Load Balancer Controller. Install it if not already present:

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

### Step 4: Run the Deployment Script

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
- AWS Region and EKS cluster name
- Full Docker image URI (from ECR or Docker Hub)
- All application environment variables (Oracle DB, Redis, service endpoints)

### Step 5: Verify Deployment

```bash
# Check all resources in the namespace
kubectl get all -n resortslite

# Check pod logs
kubectl logs -l app=resortslite -n resortslite --tail=100

# Check ingress and get the ALB hostname
kubectl get ingress -n resortslite

# Test health endpoint
INGRESS_HOST=$(kubectl get ingress resortslite-ingress -n resortslite \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
curl http://$INGRESS_HOST/actuator/health
```

### Manual Deployment (Step-by-Step)

```bash
# 1. Create namespace
kubectl apply -f kubernetes/namespace.yaml

# 2. Update deployment.yaml with your image URI
sed -i 's|{{IMAGE_URI}}|YOUR_IMAGE_URI|g' kubernetes/deployment.yaml

# 3. Apply deployment
kubectl apply -f kubernetes/deployment.yaml

# 4. Apply service
kubectl apply -f kubernetes/service.yaml

# 5. Apply ingress
kubectl apply -f kubernetes/ingress.yaml

# 6. Wait for rollout
kubectl rollout status deployment/resortslite -n resortslite
```

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` |
| `SPRING_DATASOURCE_URL` | Oracle JDBC URL | — |
| `SPRING_DATASOURCE_USERNAME` | DB username | `admin` |
| `SPRING_DATASOURCE_PASSWORD` | DB password | — |
| `REDIS_HOST` | ElastiCache endpoint | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `BOOKING_CACHE_TTL_SECONDS` | Cache TTL | `3600` |
| `PAYMENT_API_URL` | Payment service URL | — |
| `APP_PAYMENT_ENDPOINT` | Payment endpoint | — |
| `APP_INVENTORY_ENDPOINT` | Inventory endpoint | — |
| `APP_NOTIFICATION_ENDPOINT` | Notification endpoint | — |
| `REPORT_BASE_PATH` | Report file path | `/var/legacy/reports/` |
| `BACKUP_PATH` | Backup file path | `/var/legacy/backups/` |
| `JAVA_OPTS` | JVM options | See Dockerfile |

### Using Kubernetes Secrets for Sensitive Values

```bash
# Create a secret for database credentials
kubectl create secret generic resortslite-db-secret \
  --from-literal=username=admin \
  --from-literal=password=your_password \
  -n resortslite

# Reference in deployment.yaml
# env:
#   - name: SPRING_DATASOURCE_PASSWORD
#     valueFrom:
#       secretKeyRef:
#         name: resortslite-db-secret
#         key: password
```

### Using AWS Secrets Manager

```bash
# Store secret
aws secretsmanager create-secret \
  --name resortslite/db-credentials \
  --secret-string '{"username":"admin","password":"your_password"}'

# Use the AWS Secrets Manager CSI driver or External Secrets Operator
# to inject secrets into Kubernetes pods
```

---

## Health Checks and Monitoring

### Available Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /actuator/health` | Spring Boot Actuator health (liveness + readiness) |
| `GET /actuator/info` | Application info |
| `GET /health` | Custom health endpoint |

### Kubernetes Probes Configuration

The deployment is configured with:
- **Liveness Probe**: `GET /actuator/health` — restarts pod if unhealthy
- **Readiness Probe**: `GET /actuator/health` — removes pod from load balancer if not ready
- **Initial Delay**: 60s liveness / 30s readiness (JVM startup time)
- **Period**: 30s liveness / 15s readiness

### Viewing Metrics

```bash
# Pod resource usage
kubectl top pods -n resortslite

# Node resource usage
kubectl top nodes

# Describe a pod for events
kubectl describe pod -l app=resortslite -n resortslite
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

### Rolling Update

```bash
# Update image
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
# Check pod status
kubectl get pods -n resortslite

# Describe pod for events
kubectl describe pod <pod-name> -n resortslite

# Check logs
kubectl logs <pod-name> -n resortslite
kubectl logs <pod-name> -n resortslite --previous  # previous container logs
```

### Common Issues

**1. ImagePullBackOff**
```bash
# Verify ECR credentials
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin \
  123456789012.dkr.ecr.us-east-1.amazonaws.com

# Check image exists
aws ecr describe-images --repository-name resortslite --region us-east-1
```

**2. CrashLoopBackOff (JVM / Application errors)**
```bash
# Check application logs
kubectl logs <pod-name> -n resortslite

# Common causes:
# - Missing environment variables (DB URL, Redis host)
# - Oracle DB unreachable
# - Redis/ElastiCache unreachable
# - Insufficient memory (increase memory limits)
```

**3. Readiness Probe Failing**
```bash
# Test health endpoint from within the pod
kubectl exec -it <pod-name> -n resortslite -- \
  wget -qO- http://localhost:8080/actuator/health

# Check if Oracle DB and Redis are reachable
kubectl exec -it <pod-name> -n resortslite -- env | grep -E "DATASOURCE|REDIS"
```

**4. Ingress / ALB Not Provisioning**
```bash
# Check AWS Load Balancer Controller logs
kubectl logs -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller

# Verify ingress annotations
kubectl describe ingress resortslite-ingress -n resortslite

# Check IAM permissions for the controller service account
```

**5. Out of Memory (OOMKilled)**
```bash
# Increase memory limits in deployment.yaml
# resources:
#   limits:
#     memory: "2Gi"   # increase from 1Gi
# Also adjust JAVA_OPTS:
# -Xmx1536m -Xms512m
```

### Useful Debugging Commands

```bash
# Get all resources in namespace
kubectl get all -n resortslite

# Watch pods in real time
kubectl get pods -n resortslite -w

# Port-forward for local testing
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite
curl http://localhost:8080/actuator/health

# Execute shell in running pod
kubectl exec -it <pod-name> -n resortslite -- /bin/sh

# Check service endpoints
kubectl get endpoints resortslite-service -n resortslite
```

---

## Security Considerations

1. **Never commit secrets**: Use Kubernetes Secrets, AWS Secrets Manager, or AWS Parameter Store for all credentials.

2. **Non-root container**: The Dockerfile creates and uses a non-root `appuser` to run the application.

3. **Database credentials**: Rotate Oracle DB credentials regularly and store in AWS Secrets Manager.

4. **Redis/ElastiCache**: Enable in-transit encryption (TLS) and at-rest encryption for ElastiCache. Use `spring.redis.ssl=true` and update the Redis port to 6380.

5. **Network policies**: Consider adding Kubernetes NetworkPolicies to restrict pod-to-pod communication.

6. **Image scanning**: Enable ECR image scanning to detect vulnerabilities in the container image.
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name resortslite \
     --image-scanning-configuration scanOnPush=true \
     --region us-east-1
   ```

7. **HTTPS**: The ingress is configured to redirect HTTP to HTTPS. Ensure an ACM certificate is attached to the ALB.

8. **Log4Shell**: The pom.xml includes log4j-core 2.14.1 which has CVE-2021-44228. **Upgrade to log4j-core 2.17.1+** before production deployment.

9. **commons-collections**: Version 3.2.1 has CVE-2015-6420. **Upgrade to 3.2.2+**.

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with:
```
-Xms256m          # Initial heap size
-Xmx512m          # Maximum heap size
-XX:+UseContainerSupport      # Container-aware memory
-XX:MaxRAMPercentage=75.0     # Use 75% of container memory
-XX:+UnlockExperimentalVMOptions
```

Adjust these values in `kubernetes/deployment.yaml` under the `JAVA_OPTS` environment variable based on your workload.

### Spring Boot Actuator

The application exposes Spring Boot Actuator endpoints:
- `/actuator/health` — Health status (used by Kubernetes probes)
- `/actuator/info` — Application information

These are configured in `application.properties`:
```properties
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=always
```

### Spring Session with Redis

The application uses Spring Session backed by Amazon ElastiCache (Redis) for distributed session management. All EKS pods share session state, enabling horizontal scaling without sticky sessions.

Ensure the ElastiCache cluster is accessible from the EKS node group's security group.

### Oracle Database Connectivity

The application requires Oracle Database connectivity. Ensure:
1. The Oracle DB security group allows inbound traffic from the EKS node group
2. The JDBC URL uses the correct Oracle SID or service name
3. The `ojdbc8` driver is compatible with your Oracle DB version
