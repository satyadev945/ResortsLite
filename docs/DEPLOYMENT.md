# Deployment Guide — Gitpul Resort MonoCMP

## Overview

**Application**: Gitpul Resort MonoCMP  
**Framework**: Spring Boot 2.7.18  
**Java Version**: Java 8 (Amazon Corretto 8 runtime)  
**Build Tool**: Maven  
**Target Platform**: AWS EKS (Elastic Kubernetes Service)  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

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
12. [Java-Specific Notes](#java-specific-notes)

---

## Prerequisites

### Local Development
- Docker Desktop 24.x or later
- Docker Compose v2.x or later
- Java 8 JDK (for local builds outside Docker)
- Maven 3.8.x or later (for local builds outside Docker)

### AWS EKS Deployment
- AWS CLI v2.x (`aws --version`)
- kubectl v1.28+ (`kubectl version --client`)
- eksctl v0.160+ (optional, for cluster creation)
- AWS IAM permissions:
  - `eks:DescribeCluster`, `eks:UpdateKubeconfig`
  - `ecr:GetAuthorizationToken`, `ecr:BatchCheckLayerAvailability`
  - `ecr:PutImage`, `ecr:InitiateLayerUpload`, `ecr:UploadLayerPart`
  - `ecr:CompleteLayerUpload`, `ecr:CreateRepository`

---

## Project Structure

```
Gitpul_Resort_MonoCMP/
├── Dockerfile                    # Multi-stage build (Maven builder + Corretto 8 runtime)
├── docker-compose.yml            # Local development (application only)
├── .dockerignore                 # Excludes build artifacts and wrapper files
├── pom.xml                       # Maven project descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   ├── ReportService.java
│       │   └── RedisConfig.java
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml
│   ├── deployment.yaml
│   ├── service.yaml
│   └── ingress.yaml
├── scripts/
│   ├── build-push.sh             # Linux/macOS: build and push to ECR or Docker Hub
│   ├── build-push.bat            # Windows: build and push to ECR or Docker Hub
│   ├── deploy-image.sh           # Linux/macOS: deploy to AWS EKS
│   └── deploy-image.bat          # Windows: deploy to AWS EKS
└── docs/
    └── DEPLOYMENT.md             # This file
```

---

## Local Development with Docker Compose

### 1. Configure Environment Variables

Create a `.env` file in the project root (never commit this file):

```bash
# Redis connection (use a local Redis or ElastiCache endpoint)
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_SSL=false

# Payment service endpoint
PAYMENT_API_URL=http://payment-service:9090/payments/charge

# Booking cache TTL (seconds)
BOOKING_CACHE_TTL_SECONDS=3600

# Report base path
REPORT_BASE_PATH=/var/reports
```

### 2. Start the Application

```bash
# Build and start the application container
docker-compose up --build

# Run in detached mode
docker-compose up --build -d

# View logs
docker-compose logs -f gitpul-resort-monocmp

# Stop the application
docker-compose down
```

### 3. Verify the Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking creation
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=STANDARD&checkIn=2024-01-15&checkOut=2024-01-20"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=DELUXE"
```

---

## Build and Push Docker Image

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
3. Registry-specific credentials and repository details

### Windows

```cmd
# Run from project root
scripts\build-push.bat
```

### Manual Docker Build

```bash
# Build the image
docker build -t gitpul-resort-monocmp:latest .

# Tag for ECR
docker tag gitpul-resort-monocmp:latest \
  123456789012.dkr.ecr.us-east-1.amazonaws.com/gitpul-resort-monocmp:latest

# Push to ECR
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin \
  123456789012.dkr.ecr.us-east-1.amazonaws.com

docker push 123456789012.dkr.ecr.us-east-1.amazonaws.com/gitpul-resort-monocmp:latest
```

---

## AWS EKS Prerequisites

### 1. Install Required Tools

```bash
# AWS CLI v2
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip && sudo ./aws/install

# kubectl
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
chmod +x kubectl && sudo mv kubectl /usr/local/bin/

# eksctl (optional)
curl --silent --location "https://github.com/eksctl-io/eksctl/releases/latest/download/eksctl_$(uname -s)_amd64.tar.gz" | tar xz -C /tmp
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
# Add the EKS chart repo
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install the controller
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=<YOUR_CLUSTER_NAME> \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

---

## EKS Cluster Setup

### Create a New EKS Cluster (if needed)

```bash
eksctl create cluster \
  --name gitpul-resort-cluster \
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
aws eks update-kubeconfig \
  --region us-east-1 \
  --name gitpul-resort-cluster

# Verify connectivity
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

#### Windows

```cmd
scripts\deploy-image.bat
```

The script will prompt for:
- AWS Region
- EKS Cluster Name
- Full Docker image URI (e.g., `123456789012.dkr.ecr.us-east-1.amazonaws.com/gitpul-resort-monocmp:latest`)
- Application environment variables (Redis host, payment URL, etc.)

### Manual Deployment

```bash
# 1. Update deployment.yaml with your image URI
sed -i 's|{{IMAGE_URI}}|123456789012.dkr.ecr.us-east-1.amazonaws.com/gitpul-resort-monocmp:latest|g' \
  kubernetes/deployment.yaml

# 2. Update other placeholders
sed -i 's|{{REDIS_HOST}}|your-elasticache-endpoint.cache.amazonaws.com|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_PORT}}|6379|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_SSL}}|true|g' kubernetes/deployment.yaml
sed -i 's|{{PAYMENT_API_URL}}|http://payment-service.default.svc.cluster.local:9090/payments/charge|g' kubernetes/deployment.yaml
sed -i 's|{{BOOKING_CACHE_TTL_SECONDS}}|3600|g' kubernetes/deployment.yaml

# 3. Apply manifests in order
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

# 4. Wait for rollout
kubectl rollout status deployment/gitpul-resort-monocmp -n gitpul-resort-monocmp

# 5. Verify
kubectl get pods,svc,ingress -n gitpul-resort-monocmp
```

### Create Redis Secret (for Redis password)

```bash
kubectl create secret generic gitpul-resort-monocmp-secrets \
  --from-literal=redis-password=<YOUR_REDIS_PASSWORD> \
  -n gitpul-resort-monocmp
```

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Default |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` |
| `REDIS_HOST` | Redis/ElastiCache hostname | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis authentication password | _(empty)_ |
| `REDIS_SSL` | Enable Redis TLS | `false` |
| `PAYMENT_API_URL` | Payment service endpoint | `http://payment-service:9090/payments/charge` |
| `BOOKING_CACHE_TTL_SECONDS` | Booking cache TTL in seconds | `3600` |
| `REPORT_BASE_PATH` | Base path for report files | `/var/reports` |
| `JAVA_OPTS` | JVM options | `-Xmx512m -Xms256m ...` |

### Using Kubernetes ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: gitpul-resort-monocmp-config
  namespace: gitpul-resort-monocmp
data:
  REDIS_HOST: "your-elasticache.cache.amazonaws.com"
  REDIS_PORT: "6379"
  REDIS_SSL: "true"
  PAYMENT_API_URL: "http://payment-service.default.svc.cluster.local:9090/payments/charge"
  BOOKING_CACHE_TTL_SECONDS: "3600"
```

Apply: `kubectl apply -f configmap.yaml`

---

## Scaling and Management

### Horizontal Pod Autoscaler (HPA)

```bash
kubectl autoscale deployment gitpul-resort-monocmp \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n gitpul-resort-monocmp
```

### Rolling Update

```bash
# Update image
kubectl set image deployment/gitpul-resort-monocmp \
  gitpul-resort-monocmp=123456789012.dkr.ecr.us-east-1.amazonaws.com/gitpul-resort-monocmp:v2.0.0 \
  -n gitpul-resort-monocmp

# Monitor rollout
kubectl rollout status deployment/gitpul-resort-monocmp -n gitpul-resort-monocmp
```

### Rollback

```bash
# Rollback to previous version
kubectl rollout undo deployment/gitpul-resort-monocmp -n gitpul-resort-monocmp

# Rollback to specific revision
kubectl rollout undo deployment/gitpul-resort-monocmp \
  --to-revision=2 \
  -n gitpul-resort-monocmp

# View rollout history
kubectl rollout history deployment/gitpul-resort-monocmp -n gitpul-resort-monocmp
```

### Scale Manually

```bash
kubectl scale deployment gitpul-resort-monocmp --replicas=4 -n gitpul-resort-monocmp
```

---

## Troubleshooting

### Pod Not Starting

```bash
# Check pod status
kubectl get pods -n gitpul-resort-monocmp

# Describe pod for events
kubectl describe pod <POD_NAME> -n gitpul-resort-monocmp

# View pod logs
kubectl logs <POD_NAME> -n gitpul-resort-monocmp

# View previous container logs (if crashed)
kubectl logs <POD_NAME> -n gitpul-resort-monocmp --previous
```

### Common Issues

**Issue: Pod in `CrashLoopBackOff`**
- Check logs: `kubectl logs <POD_NAME> -n gitpul-resort-monocmp`
- Likely cause: Redis connection failure — verify `REDIS_HOST` and `REDIS_PORT` are correct
- Verify the Redis secret exists: `kubectl get secret gitpul-resort-monocmp-secrets -n gitpul-resort-monocmp`

**Issue: Pod in `Pending` state**
- Check node resources: `kubectl describe nodes`
- Reduce resource requests in `deployment.yaml` if cluster is under-resourced

**Issue: Health check failing**
- JVM startup takes 30–60 seconds — `initialDelaySeconds: 60` is set for liveness probe
- Check actuator endpoint: `kubectl exec -it <POD_NAME> -n gitpul-resort-monocmp -- wget -qO- http://localhost:8080/actuator/health`

**Issue: Ingress not getting an address**
- Verify AWS Load Balancer Controller is installed: `kubectl get pods -n kube-system | grep aws-load-balancer`
- Check ingress events: `kubectl describe ingress gitpul-resort-monocmp-ingress -n gitpul-resort-monocmp`

**Issue: Redis connection refused**
- Verify ElastiCache security group allows inbound on port 6379 from EKS node security group
- Test connectivity from pod: `kubectl exec -it <POD_NAME> -n gitpul-resort-monocmp -- nc -zv $REDIS_HOST 6379`

### Service Connectivity

```bash
# Port-forward for local testing
kubectl port-forward svc/gitpul-resort-monocmp-service 8080:80 -n gitpul-resort-monocmp

# Test health endpoint
curl http://localhost:8080/actuator/health
```

---

## Security Considerations

1. **Non-root container**: The application runs as `appuser` (UID 1000) — never as root.
2. **Redis password**: Store in Kubernetes Secret, not ConfigMap. Reference via `secretKeyRef`.
3. **Image scanning**: Enable ECR image scanning on push to detect vulnerabilities.
4. **Network policies**: Restrict pod-to-pod communication using Kubernetes NetworkPolicy.
5. **IRSA (IAM Roles for Service Accounts)**: Use IRSA for pod-level AWS permissions instead of node-level IAM roles.
6. **TLS**: Enable `REDIS_SSL=true` when connecting to Amazon ElastiCache in production.
7. **Secrets rotation**: Use AWS Secrets Manager with the External Secrets Operator for automatic secret rotation.

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with:
```
-Xmx512m          # Maximum heap size
-Xms256m          # Initial heap size
-XX:+UseContainerSupport    # Respect container memory limits
-XX:MaxRAMPercentage=75.0   # Use 75% of container memory for JVM
```

Adjust `JAVA_OPTS` in `deployment.yaml` based on your workload. With a 1Gi memory limit, the JVM will use up to 768Mi.

### Spring Boot Actuator

Health endpoints exposed:
- `GET /actuator/health` — Liveness and readiness probe target
- `GET /actuator/info` — Application information

### Spring Session with Redis

This application uses Spring Session backed by Amazon ElastiCache (Redis) for distributed HTTP session storage. All pod replicas share session state — no sticky sessions required on the load balancer.

### Startup Time

Java 8 Spring Boot applications typically take 20–45 seconds to start. The Kubernetes probes are configured with:
- Liveness probe: `initialDelaySeconds: 60`
- Readiness probe: `initialDelaySeconds: 30`

Adjust these values if your cluster environment is slower.

### Graceful Shutdown

The deployment is configured with `terminationGracePeriodSeconds: 30`. Spring Boot 2.7.x supports graceful shutdown — in-flight requests will complete before the pod terminates.
