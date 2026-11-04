# ResortsLite — Deployment Guide

## Overview

This guide covers building, containerizing, and deploying the **ResortsLite** Spring Boot application (Java 8 / Spring Boot 2.7.x) to **AWS EKS** (Elastic Kubernetes Service).

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Project Structure](#project-structure)
3. [Local Development with Docker Compose](#local-development-with-docker-compose)
4. [Build and Push Docker Image](#build-and-push-docker-image)
5. [AWS EKS Prerequisites](#aws-eks-prerequisites)
6. [EKS Cluster Setup](#eks-cluster-setup)
7. [Kubernetes Deployment Walkthrough](#kubernetes-deployment-walkthrough)
8. [Deploy with Script](#deploy-with-script)
9. [Configuration Management](#configuration-management)
10. [Scaling and Management](#scaling-and-management)
11. [Troubleshooting](#troubleshooting)
12. [Security Considerations](#security-considerations)
13. [Java-Specific Notes](#java-specific-notes)

---

## Prerequisites

### Local Development Tools

| Tool | Version | Purpose |
|------|---------|---------|
| Docker | 20.10+ | Container build and run |
| Docker Compose | 2.x | Local multi-container orchestration |
| Java JDK | 8 | Local development and testing |
| Maven | 3.8+ | Build tool |
| AWS CLI | 2.x | AWS resource management |
| kubectl | 1.27+ | Kubernetes cluster management |
| eksctl | 0.150+ | EKS cluster provisioning |

### AWS Requirements

- AWS Account with appropriate IAM permissions
- IAM user/role with: `AmazonEKSClusterPolicy`, `AmazonEKSWorkerNodePolicy`, `AmazonEC2ContainerRegistryFullAccess`
- VPC with public/private subnets (recommended: 3 AZs)
- Amazon ElastiCache for Redis (for session store and booking cache)
- Oracle RDS or Oracle DB accessible from EKS VPC

---

## Project Structure

```
TEST/
├── Dockerfile                    # Multi-stage Docker build
├── .dockerignore                 # Docker build exclusions
├── docker-compose.yml            # Local development compose file
├── pom.xml                       # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
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
    └── DEPLOYMENT.md             # This guide
```

---

## Local Development with Docker Compose

### 1. Configure Environment Variables

Create a `.env` file in the project root (never commit this file):

```bash
# Database
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=your_password

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_SSL=false

# Cache
BOOKING_CACHE_TTL_SECONDS=3600

# Service endpoints
PAYMENT_API_URL=http://payment-svc.default.svc.cluster.local:9090/payments/charge
APP_INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://notify.internal:7070/send

# File paths
REPORTS_BASE_PATH=/var/reports
BACKUP_PATH=/var/backups/resort/nightly
```

### 2. Build and Start

```bash
# Build and start the application container
docker-compose up --build

# Run in background
docker-compose up -d --build

# View logs
docker-compose logs -f resortslite

# Stop
docker-compose down
```

### 3. Verify Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05"
```

---

## Build and Push Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
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

### Manual Build

```bash
# Build image
docker build -t resortslite:latest .

# Tag for ECR
docker tag resortslite:latest <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/resortslite:latest

# Push to ECR
aws ecr get-login-password --region <REGION> | docker login --username AWS --password-stdin <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com
docker push <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/resortslite:latest
```

---

## AWS EKS Prerequisites

### 1. Install Required Tools

```bash
# AWS CLI
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip && sudo ./aws/install

# kubectl
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
chmod +x kubectl && sudo mv kubectl /usr/local/bin/

# eksctl
curl --silent --location "https://github.com/weaveworks/eksctl/releases/latest/download/eksctl_$(uname -s)_amd64.tar.gz" | tar xz -C /tmp
sudo mv /tmp/eksctl /usr/local/bin
```

### 2. Configure AWS CLI

```bash
aws configure
# Enter: AWS Access Key ID, Secret Access Key, Region, Output format
```

### 3. Install AWS Load Balancer Controller

The ingress manifest uses the AWS Load Balancer Controller. Install it on your EKS cluster:

```bash
# Create IAM policy
curl -O https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v2.6.0/docs/install/iam_policy.json
aws iam create-policy --policy-name AWSLoadBalancerControllerIAMPolicy --policy-document file://iam_policy.json

# Install via Helm
helm repo add eks https://aws.github.io/eks-charts
helm repo update
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=<CLUSTER_NAME> \
  --set serviceAccount.create=true \
  --set serviceAccount.annotations."eks\.amazonaws\.com/role-arn"=arn:aws:iam::<ACCOUNT_ID>:role/AmazonEKSLoadBalancerControllerRole
```

---

## EKS Cluster Setup

### Create EKS Cluster

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

## Kubernetes Deployment Walkthrough

### Manifest Descriptions

| File | Kind | Description |
|------|------|-------------|
| `namespace.yaml` | Namespace | Isolates ResortsLite resources in `resortslite` namespace |
| `deployment.yaml` | Deployment | Runs 2 replicas of the application with health probes |
| `service.yaml` | Service | ClusterIP service exposing port 80 → 8080 |
| `ingress.yaml` | Ingress | AWS ALB ingress with HTTPS redirect |

### Manual Deployment

```bash
# 1. Create namespace
kubectl apply -f kubernetes/namespace.yaml

# 2. Update image URI in deployment.yaml
sed -i 's|{{IMAGE_URI}}|<YOUR_IMAGE_URI>|g' kubernetes/deployment.yaml

# 3. Update environment variable placeholders
sed -i 's|{{REDIS_HOST}}|<YOUR_REDIS_HOST>|g' kubernetes/deployment.yaml
# ... repeat for all placeholders

# 4. Apply deployment
kubectl apply -f kubernetes/deployment.yaml

# 5. Apply service
kubectl apply -f kubernetes/service.yaml

# 6. Apply ingress
kubectl apply -f kubernetes/ingress.yaml

# 7. Verify
kubectl get pods,svc,ingress -n resortslite
```

---

## Deploy with Script

### Linux / macOS

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

### Windows

```cmd
scripts\deploy-image.bat
```

The script will prompt for:
- AWS Region and EKS Cluster Name
- Full Docker image URI
- All application environment variables (database, Redis, service endpoints, file paths)

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_DATASOURCE_URL` | Oracle JDBC connection URL | — |
| `SPRING_DATASOURCE_USERNAME` | Database username | `admin` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | — |
| `REDIS_HOST` | ElastiCache Redis endpoint | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis auth password | — |
| `REDIS_SSL` | Enable Redis TLS | `false` |
| `BOOKING_CACHE_TTL_SECONDS` | Booking cache TTL | `3600` |
| `PAYMENT_API_URL` | Payment service URL | cluster DNS |
| `APP_INVENTORY_ENDPOINT` | Inventory service URL | — |
| `APP_NOTIFICATION_ENDPOINT` | Notification service URL | — |
| `REPORTS_BASE_PATH` | Reports directory path | `/var/reports` |
| `BACKUP_PATH` | Backup directory path | `/var/backups/resort/nightly` |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` |
| `JAVA_OPTS` | JVM options | See Dockerfile |

### Using AWS Secrets Manager

For sensitive values (database password, Redis password), use AWS Secrets Manager with the External Secrets Operator:

```bash
# Install External Secrets Operator
helm repo add external-secrets https://charts.external-secrets.io
helm install external-secrets external-secrets/external-secrets -n external-secrets --create-namespace
```

---

## Scaling and Management

### Horizontal Pod Autoscaler

```bash
kubectl autoscale deployment resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortslite
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
# Check pod status
kubectl get pods -n resortslite

# Describe pod for events
kubectl describe pod <POD_NAME> -n resortslite

# View pod logs
kubectl logs <POD_NAME> -n resortslite
kubectl logs <POD_NAME> -n resortslite --previous  # crashed pod
```

### Health Check Failures

```bash
# Check actuator health endpoint
kubectl exec -it <POD_NAME> -n resortslite -- wget -qO- http://localhost:8080/actuator/health

# Check liveness/readiness probe configuration
kubectl describe deployment resortslite -n resortslite
```

### Redis Connection Issues

```bash
# Verify Redis environment variables
kubectl exec -it <POD_NAME> -n resortslite -- env | grep REDIS

# Test Redis connectivity from pod
kubectl exec -it <POD_NAME> -n resortslite -- sh -c "nc -zv $REDIS_HOST $REDIS_PORT"
```

### Database Connection Issues

```bash
# Verify datasource environment variables
kubectl exec -it <POD_NAME> -n resortslite -- env | grep SPRING_DATASOURCE

# Check application logs for connection errors
kubectl logs <POD_NAME> -n resortslite | grep -i "datasource\|connection\|oracle"
```

### Ingress / ALB Issues

```bash
# Check ingress status
kubectl describe ingress resortslite-ingress -n resortslite

# Check ALB controller logs
kubectl logs -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller

# Verify ALB is provisioned
kubectl get ingress resortslite-ingress -n resortslite -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
```

### OOMKilled (Out of Memory)

```bash
# Check resource usage
kubectl top pods -n resortslite

# Increase memory limits in deployment.yaml
# resources.limits.memory: "2Gi"
# Also increase JAVA_OPTS: -Xmx1g -Xms512m
```

---

## Security Considerations

1. **Never commit secrets** to source control — use AWS Secrets Manager or Kubernetes Secrets
2. **Use IRSA** (IAM Roles for Service Accounts) for pod-level AWS permissions
3. **Enable Redis TLS** (`REDIS_SSL=true`) for ElastiCache in production
4. **Use HTTPS** — the ingress manifest includes SSL redirect annotations
5. **Non-root container** — the Dockerfile creates and uses a non-root `appuser`
6. **Network policies** — restrict pod-to-pod communication in production
7. **Rotate credentials** regularly using AWS Secrets Manager rotation
8. **Upgrade vulnerable dependencies** — log4j 2.14.1 (CVE-2021-44228) and commons-collections 3.2.1 (CVE-2015-6420) present in pom.xml must be updated before production deployment

---

## Java-Specific Notes

### JVM Container Optimizations

The Dockerfile sets the following JVM flags optimized for containerized environments:

```
-Xmx512m -Xms256m
-XX:+UseContainerSupport          # Respect container memory limits
-XX:MaxRAMPercentage=75.0         # Use 75% of container memory as max heap
-XX:+ExitOnOutOfMemoryError       # Fail fast on OOM
-Djava.security.egd=file:/dev/./urandom  # Faster random number generation
-Dfile.encoding=UTF-8
-Duser.timezone=UTC
```

### Spring Boot Actuator

The application exposes `/actuator/health` for Kubernetes liveness and readiness probes. The management endpoint is configured in `application.properties`:

```properties
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=always
```

### Spring Session with Redis

The application uses Spring Session backed by Amazon ElastiCache for Redis (`@EnableRedisHttpSession`). All HTTP sessions are stored externally, enabling stateless horizontal scaling across EKS pod replicas.

### JVM Startup Time

Spring Boot applications on Java 8 typically take 30–60 seconds to start. The Kubernetes probes are configured with:
- `initialDelaySeconds: 60` for liveness probe
- `initialDelaySeconds: 30` for readiness probe

Adjust these values based on observed startup times in your environment.

### Heap Sizing

For production workloads, monitor heap usage and adjust:
- `JAVA_OPTS=-Xmx1g -Xms512m` for higher traffic
- Increase `resources.limits.memory` to `2Gi` accordingly
