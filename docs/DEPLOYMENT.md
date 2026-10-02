# ResortsLite — Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Building and Pushing the Docker Image](#building-and-pushing-the-docker-image)
6. [AWS EKS Deployment](#aws-eks-deployment)
7. [Configuration Management](#configuration-management)
8. [Scaling and Management](#scaling-and-management)
9. [Troubleshooting](#troubleshooting)
10. [Security Considerations](#security-considerations)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.18  
**Java Version**: 8  
**Build Tool**: Maven  
**Base Image (Runtime)**: `eclipse-temurin:8-jdk-alpine`  
**Target Platform**: AWS EKS (Elastic Kubernetes Service)  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

ResortsLite is a legacy resort booking application modernized for cloud-native deployment on AWS EKS. It uses Amazon ElastiCache (Redis) for distributed session management and an H2 in-memory database for local development.

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
| AWS CLI | 2.x | AWS authentication and ECR operations |
| kubectl | 1.27+ | Kubernetes cluster management |
| eksctl | 0.150+ | EKS cluster creation (optional) |

### AWS IAM Permissions Required
```
ecr:GetAuthorizationToken
ecr:BatchCheckLayerAvailability
ecr:GetDownloadUrlForLayer
ecr:BatchGetImage
ecr:CreateRepository
ecr:PutImage
ecr:InitiateLayerUpload
ecr:UploadLayerPart
ecr:CompleteLayerUpload
eks:DescribeCluster
eks:ListClusters
```

---

## Project Structure

```
COmp test contaier/
├── Dockerfile                    # Multi-stage Docker build
├── docker-compose.yml            # Local development compose file
├── .dockerignore                 # Docker build context exclusions
├── pom.xml                       # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   ├── RedisSessionConfig.java
│       │   └── ReportService.java
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
# Redis connection (use a local Redis container or ElastiCache endpoint)
REDIS_HOST=localhost
REDIS_PORT=6379

# File paths
REPORT_BASE_PATH=/var/reports
BACKUP_PATH=/var/backups/resorts

# Payment service
PAYMENT_API_URL=http://payment-svc.payments.svc.cluster.local:9090/payments/charge
```

### 2. Start the Application

```bash
# Build and start
docker compose up --build

# Start in background
docker compose up -d --build

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

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=DELUXE"
```

---

## Building and Pushing the Docker Image

### Linux / macOS

```bash
# Make the script executable
chmod +x scripts/build-push.sh

# Run from repository root
./scripts/build-push.sh
```

The script will prompt you to:
1. Enter an image tag (default: `latest`)
2. Select registry type: **AWS ECR** or **Docker Hub**
3. Provide registry-specific credentials

### Windows

```cmd
scripts\build-push.bat
```

### Manual Build (without script)

```bash
# Build image
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
  --nodes-max 4
```

**Connect to an existing cluster:**
```bash
aws eks update-kubeconfig --region us-east-1 --name resortslite-cluster
kubectl cluster-info
```

### Step 3: Install AWS Load Balancer Controller

The ingress manifest uses the AWS Load Balancer Controller. Install it on your EKS cluster:

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

> **Note**: The AWS Load Balancer Controller requires an IAM role with appropriate permissions. See the [official documentation](https://docs.aws.amazon.com/eks/latest/userguide/aws-load-balancer-controller.html).

### Step 4: Set Up Amazon ElastiCache (Redis)

ResortsLite requires a Redis instance for session management:

```bash
# Create ElastiCache Redis cluster (via AWS Console or CLI)
aws elasticache create-cache-cluster \
  --cache-cluster-id resortslite-redis \
  --cache-node-type cache.t3.micro \
  --engine redis \
  --num-cache-nodes 1 \
  --region us-east-1
```

Note the **Primary Endpoint** — you will need it during deployment.

### Step 5: Deploy to EKS

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
- AWS Region
- EKS Cluster Name
- Docker image URI (full path with tag)
- `REDIS_HOST` — ElastiCache primary endpoint
- `REDIS_PORT` — default `6379`
- `PAYMENT_API_URL` — payment service endpoint
- `REPORT_BASE_PATH` — report file path
- `BACKUP_PATH` — backup file path

### Step 6: Verify Deployment

```bash
# Check pods
kubectl get pods -n resortslite

# Check services
kubectl get svc -n resortslite

# Check ingress (wait for ALB to provision)
kubectl get ingress -n resortslite

# View pod logs
kubectl logs -f deployment/resortslite -n resortslite

# Health check via port-forward
kubectl port-forward deployment/resortslite 8080:8080 -n resortslite
curl http://localhost:8080/actuator/health
```

### Step 7: Access the Application

Once the ALB is provisioned (may take 2–5 minutes):

```bash
# Get the ALB hostname
kubectl get ingress resortslite-ingress -n resortslite \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
```

Access the application at: `http://<ALB_HOSTNAME>/api/bookings/availability?roomType=STANDARD`

---

## Configuration Management

### Environment Variables Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `REDIS_HOST` | `localhost` | ElastiCache primary endpoint |
| `REDIS_PORT` | `6379` | Redis port |
| `PAYMENT_API_URL` | `http://payment-svc.payments.svc.cluster.local:9090/payments/charge` | Payment service URL |
| `REPORT_BASE_PATH` | `/var/reports` | Base path for report files |
| `BACKUP_PATH` | `/var/backups/resorts` | Backup directory path |
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `JAVA_OPTS` | `-Xms256m -Xmx512m ...` | JVM options |
| `TZ` | `UTC` | Container timezone |

### Using Kubernetes ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: resortslite-config
  namespace: resortslite
data:
  REDIS_HOST: "my-cluster.abc123.ng.0001.use1.cache.amazonaws.com"
  REDIS_PORT: "6379"
  REPORT_BASE_PATH: "/var/reports"
  BACKUP_PATH: "/var/backups/resorts"
```

Apply and reference in deployment:
```bash
kubectl apply -f kubernetes/configmap.yaml
```

### Using Kubernetes Secrets (for sensitive values)

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: resortslite-secrets
  namespace: resortslite
type: Opaque
stringData:
  PAYMENT_API_URL: "https://payment-svc.internal:9090/payments/charge"
```

---

## Scaling and Management

### Horizontal Pod Autoscaler (HPA)

```bash
kubectl autoscale deployment resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortslite

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
  resortslite=123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:v2.0.0 \
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

# Check pod logs
kubectl logs -l app=resortslite -n resortslite --previous

# Check resource constraints
kubectl top pods -n resortslite
```

### Redis Connection Issues

```bash
# Verify REDIS_HOST is set correctly
kubectl exec -it deployment/resortslite -n resortslite -- env | grep REDIS

# Test Redis connectivity from pod
kubectl exec -it deployment/resortslite -n resortslite -- sh -c "nc -zv $REDIS_HOST $REDIS_PORT"
```

### Health Check Failures

```bash
# Port-forward and test health endpoint
kubectl port-forward deployment/resortslite 8080:8080 -n resortslite
curl -v http://localhost:8080/actuator/health

# Check liveness/readiness probe events
kubectl describe pod -l app=resortslite -n resortslite | grep -A 10 "Liveness\|Readiness"
```

### Ingress / ALB Issues

```bash
# Check ingress status
kubectl describe ingress resortslite-ingress -n resortslite

# Check AWS Load Balancer Controller logs
kubectl logs -n kube-system deployment/aws-load-balancer-controller

# Verify security groups allow traffic on port 80/443
aws ec2 describe-security-groups --filters "Name=tag:kubernetes.io/cluster/resortslite-cluster,Values=owned"
```

### JVM Memory Issues

If pods are OOMKilled, increase memory limits in `kubernetes/deployment.yaml`:

```yaml
resources:
  requests:
    cpu: "500m"
    memory: "768Mi"
  limits:
    cpu: "1000m"
    memory: "1536Mi"
```

Also adjust `JAVA_OPTS`:
```
-Xms512m -Xmx1g -XX:MaxRAMPercentage=75.0
```

---

## Security Considerations

1. **Never commit secrets** to source control. Use AWS Secrets Manager or Kubernetes Secrets.
2. **Use HTTPS** for all external traffic. Configure TLS termination at the ALB.
3. **Restrict IAM permissions** using least-privilege policies for the EKS node role.
4. **Enable VPC security groups** to restrict Redis access to the EKS node security group only.
5. **Rotate credentials** regularly and use IRSA (IAM Roles for Service Accounts) for pod-level AWS access.
6. **Scan images** for vulnerabilities using Amazon ECR image scanning or Trivy before deployment.
7. **Note**: The current codebase contains known security violations (SQL injection, MD5 hashing, hardcoded credentials in `BookingService.java`) that should be remediated before production deployment.

---

## Java-Specific Notes

- **JVM Startup**: Spring Boot on Java 8 may take 30–60 seconds to start. The `initialDelaySeconds: 60` in liveness probes accounts for this.
- **Container Support**: `-XX:+UseContainerSupport` ensures the JVM respects container memory limits rather than host memory.
- **MaxRAMPercentage**: Set to `75.0` to leave headroom for non-heap memory (Metaspace, threads, native memory).
- **Graceful Shutdown**: `terminationGracePeriodSeconds: 30` allows in-flight requests to complete before pod termination.
- **Session Timeout**: Default Redis session TTL is 1800 seconds (30 minutes), configurable via `SESSION_TIMEOUT_SECONDS` env var.
