# ResortsLite — Deployment Guide

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.x  
**Java Version**: Java 8  
**Build Tool**: Maven  
**Target Platform**: AWS EKS (Elastic Kubernetes Service)  
**Container Registry**: AWS ECR or Docker Hub  

ResortsLite is a legacy resort booking REST API modernised for cloud-native deployment on AWS EKS. It uses Spring Session backed by Amazon ElastiCache (Redis) for stateless horizontal scaling, and connects to an Oracle database via environment-variable-driven configuration.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Project Structure](#project-structure)
3. [Local Development with Docker Compose](#local-development-with-docker-compose)
4. [Build and Push Docker Image](#build-and-push-docker-image)
5. [AWS EKS Prerequisites](#aws-eks-prerequisites)
6. [EKS Cluster Setup](#eks-cluster-setup)
7. [Kubernetes Deployment Walkthrough](#kubernetes-deployment-walkthrough)
8. [Environment Variables Reference](#environment-variables-reference)
9. [Health Checks and Monitoring](#health-checks-and-monitoring)
10. [Scaling and Management](#scaling-and-management)
11. [Troubleshooting](#troubleshooting)
12. [Security Considerations](#security-considerations)
13. [Configuration Management](#configuration-management)

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker | 20.10+ | Container build and run |
| Docker Compose | 2.x | Local multi-container orchestration |
| Java JDK | 8 | Local build (optional) |
| Maven | 3.8+ | Local build (optional) |

### AWS EKS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS authentication and ECR access |
| kubectl | 1.27+ | Kubernetes cluster management |
| eksctl | 0.150+ | EKS cluster provisioning (optional) |
| Helm | 3.x | AWS Load Balancer Controller installation |

### AWS IAM Permissions Required
- `ecr:GetAuthorizationToken`
- `ecr:BatchCheckLayerAvailability`
- `ecr:PutImage`
- `ecr:InitiateLayerUpload`
- `ecr:UploadLayerPart`
- `ecr:CompleteLayerUpload`
- `ecr:CreateRepository`
- `eks:DescribeCluster`
- `eks:UpdateKubeconfig`

---

## Project Structure

```
TEST/
├── Dockerfile                  # Multi-stage build (Java 8 / amazoncorretto:8)
├── docker-compose.yml          # Local development (application only)
├── .dockerignore               # Excludes build artifacts and wrapper files
├── pom.xml                     # Maven build descriptor
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
│   ├── namespace.yaml          # Kubernetes namespace
│   ├── deployment.yaml         # Application deployment (2 replicas)
│   ├── service.yaml            # ClusterIP service
│   └── ingress.yaml            # AWS ALB Ingress
├── scripts/
│   ├── build-push.sh           # Linux/macOS: build and push image
│   ├── build-push.bat          # Windows: build and push image
│   ├── deploy-image.sh         # Linux/macOS: deploy to EKS
│   └── deploy-image.bat        # Windows: deploy to EKS
└── docs/
    └── DEPLOYMENT.md           # This file
```

---

## Local Development with Docker Compose

Docker Compose runs the application container only. External services (Oracle DB, Redis) must be provided separately or mocked.

### Step 1: Create a `.env` file

```bash
# .env (do NOT commit to source control)
REDIS_HOST=your-elasticache-endpoint.cache.amazonaws.com
REDIS_PORT=6379
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@your-db-host:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=your-secure-password
PAYMENT_API_URL=http://payment-service:9090/payments/charge
APP_PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge
APP_INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://notify.internal:7070/send
REPORT_BASE_PATH=/var/reports
```

### Step 2: Start the application

```bash
docker-compose up --build
```

### Step 3: Verify the application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=STANDARD&checkIn=2024-01-15&checkOut=2024-01-20"
```

### Step 4: Stop the application

```bash
docker-compose down
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
2. Registry type: `1` for AWS ECR, `2` for Docker Hub
3. Registry-specific credentials

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

# Authenticate
aws ecr get-login-password --region $AWS_REGION | \
  docker login --username AWS --password-stdin \
  ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com

# Create repository (first time only)
aws ecr create-repository --repository-name resortslite --region $AWS_REGION

# Build and push
docker build -t ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/resortslite:${IMAGE_TAG} .
docker push ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/resortslite:${IMAGE_TAG}
```

---

## AWS EKS Prerequisites

### 1. Install AWS CLI

```bash
# macOS
brew install awscli

# Linux
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip && sudo ./aws/install

# Configure
aws configure
```

### 2. Install kubectl

```bash
# macOS
brew install kubectl

# Linux
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
chmod +x kubectl && sudo mv kubectl /usr/local/bin/
```

### 3. Install AWS Load Balancer Controller

The ingress manifest uses the AWS Load Balancer Controller. Install it on your EKS cluster:

```bash
# Add Helm repo
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install controller (replace with your cluster details)
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=<YOUR_CLUSTER_NAME> \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

---

## EKS Cluster Setup

### Create EKS Cluster (if not existing)

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
```

---

## Kubernetes Deployment Walkthrough

### Automated Deployment

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
- AWS Region and EKS cluster name
- Full Docker image URI
- All required environment variables (Redis, Oracle DB, service endpoints)

### Manual Deployment

#### Step 1: Apply namespace

```bash
kubectl apply -f kubernetes/namespace.yaml
```

#### Step 2: Update deployment manifest

Edit `kubernetes/deployment.yaml` and replace all `{{PLACEHOLDER}}` values:

| Placeholder | Description |
|-------------|-------------|
| `{{IMAGE_URI}}` | Full Docker image URI with tag |
| `{{REDIS_HOST}}` | Amazon ElastiCache primary endpoint |
| `{{REDIS_PORT}}` | ElastiCache port (default: 6379) |
| `{{SPRING_DATASOURCE_URL}}` | Oracle JDBC connection URL |
| `{{SPRING_DATASOURCE_USERNAME}}` | Database username |
| `{{SPRING_DATASOURCE_PASSWORD}}` | Database password |
| `{{PAYMENT_API_URL}}` | Payment service URL |
| `{{APP_PAYMENT_ENDPOINT}}` | Payment charge endpoint |
| `{{APP_INVENTORY_ENDPOINT}}` | Inventory service endpoint |
| `{{APP_NOTIFICATION_ENDPOINT}}` | Notification service endpoint |
| `{{REPORT_BASE_PATH}}` | Report file storage path |

#### Step 3: Apply manifests

```bash
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml
```

#### Step 4: Verify deployment

```bash
kubectl rollout status deployment/resortslite -n resortslite
kubectl get pods,svc,ingress -n resortslite
```

#### Step 5: Get application URL

```bash
kubectl get ingress resortslite-ingress -n resortslite \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
```

---

## Environment Variables Reference

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | No | `docker` | Spring active profile |
| `REDIS_HOST` | Yes | `localhost` | Amazon ElastiCache primary endpoint |
| `REDIS_PORT` | No | `6379` | ElastiCache port |
| `SPRING_DATASOURCE_URL` | Yes | — | Oracle JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Yes | — | Database username |
| `SPRING_DATASOURCE_PASSWORD` | Yes | — | Database password |
| `PAYMENT_API_URL` | No | `http://payment-service:9090/payments/charge` | Payment service URL |
| `APP_PAYMENT_ENDPOINT` | No | `http://payment-svc.internal:9090/charge` | Payment charge endpoint |
| `APP_INVENTORY_ENDPOINT` | No | `http://inventory-svc.internal:8081/rooms` | Inventory service endpoint |
| `APP_NOTIFICATION_ENDPOINT` | No | `http://notify.internal:7070/send` | Notification service endpoint |
| `REPORT_BASE_PATH` | No | `/var/reports` | Report file storage path |
| `JAVA_OPTS` | No | See Dockerfile | JVM tuning flags |
| `TZ` | No | `UTC` | Container timezone |

---

## Health Checks and Monitoring

### Spring Boot Actuator Endpoints

| Endpoint | URL | Description |
|----------|-----|-------------|
| Health | `GET /actuator/health` | Application health status |

### Kubernetes Probes

The deployment configures:
- **Liveness Probe**: `GET /actuator/health` — restarts pod if unhealthy
- **Readiness Probe**: `GET /actuator/health` — removes pod from load balancer if not ready

Probe timings are tuned for JVM startup:
- `initialDelaySeconds: 90` (liveness) / `60` (readiness)
- `periodSeconds: 30` (liveness) / `15` (readiness)

### Check pod health manually

```bash
kubectl describe pod -l app=resortslite -n resortslite
kubectl logs -l app=resortslite -n resortslite --tail=100
```

---

## Scaling and Management

### Manual Scaling

```bash
kubectl scale deployment resortslite --replicas=4 -n resortslite
```

### Horizontal Pod Autoscaler (HPA)

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

### Pod not starting

```bash
# Check pod status
kubectl get pods -n resortslite

# Describe pod for events
kubectl describe pod <POD_NAME> -n resortslite

# Check logs
kubectl logs <POD_NAME> -n resortslite
kubectl logs <POD_NAME> -n resortslite --previous  # crashed pod logs
```

### Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| `CrashLoopBackOff` | JVM OOM or missing env vars | Check logs; verify all required env vars are set |
| `ImagePullBackOff` | ECR auth expired or wrong image URI | Re-authenticate ECR; verify image URI |
| `Pending` pods | Insufficient cluster resources | Scale node group or reduce resource requests |
| Redis connection refused | Wrong `REDIS_HOST` or security group | Verify ElastiCache endpoint and EKS security group rules |
| Oracle connection timeout | Wrong JDBC URL or security group | Verify `SPRING_DATASOURCE_URL` and RDS security group |
| Ingress not getting hostname | ALB controller not installed | Install AWS Load Balancer Controller |

### Redis / ElastiCache Connectivity

```bash
# Test Redis connectivity from within a pod
kubectl exec -it <POD_NAME> -n resortslite -- sh
# Inside pod: nc -zv $REDIS_HOST $REDIS_PORT
```

### Database Connectivity

```bash
# Check datasource configuration
kubectl exec -it <POD_NAME> -n resortslite -- sh
# Inside pod: check env vars
env | grep SPRING_DATASOURCE
```

---

## Security Considerations

1. **Secrets Management**: Store `SPRING_DATASOURCE_PASSWORD` and other sensitive values in AWS Secrets Manager or Kubernetes Secrets — never in plain ConfigMaps.

   ```bash
   kubectl create secret generic resortslite-secrets \
     --from-literal=SPRING_DATASOURCE_PASSWORD=<password> \
     --from-literal=SPRING_DATASOURCE_USERNAME=<username> \
     -n resortslite
   ```

2. **Non-root Container**: The Dockerfile creates and uses a non-root `appuser` for security.

3. **Network Policies**: Restrict pod-to-pod communication using Kubernetes NetworkPolicies.

4. **IRSA (IAM Roles for Service Accounts)**: Use IRSA for secure access to AWS services (ElastiCache, RDS) without embedding credentials.

5. **Image Scanning**: Enable ECR image scanning to detect vulnerabilities in the container image.

   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name resortslite \
     --image-scanning-configuration scanOnPush=true \
     --region us-east-1
   ```

6. **TLS/HTTPS**: The ingress is configured to redirect HTTP to HTTPS. Ensure an ACM certificate is associated with the ALB.

---

## Configuration Management

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
  PAYMENT_API_URL: "http://payment-service:9090/payments/charge"
  REPORT_BASE_PATH: "/var/reports"
```

```bash
kubectl apply -f configmap.yaml
```

Reference in deployment:

```yaml
envFrom:
  - configMapRef:
      name: resortslite-config
  - secretRef:
      name: resortslite-secrets
```

### Java-Specific Notes

- **JVM Memory**: The container is configured with `-XX:+UseContainerSupport` and `-XX:MaxRAMPercentage=75.0` so the JVM respects container memory limits automatically.
- **Startup Time**: Java 8 applications may take 60–90 seconds to start. The liveness probe `initialDelaySeconds` is set to 90 seconds to accommodate this.
- **Spring Session**: Session data is stored in Amazon ElastiCache (Redis). Ensure the EKS node security group allows outbound traffic to the ElastiCache security group on port 6379.
- **Oracle Driver**: The `ojdbc8` driver is included via Maven. Ensure the Oracle DB is accessible from the EKS VPC.
- **Graceful Shutdown**: The container uses `terminationGracePeriodSeconds: 60` to allow in-flight requests to complete before pod termination.
