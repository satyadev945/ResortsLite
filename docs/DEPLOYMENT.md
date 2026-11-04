# ResortsLite — Deployment Guide (AWS EKS)

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Build and Push Docker Image](#build-and-push-docker-image)
6. [AWS EKS Deployment](#aws-eks-deployment)
7. [Kubernetes Manifest Reference](#kubernetes-manifest-reference)
8. [Configuration Management](#configuration-management)
9. [Scaling and Management](#scaling-and-management)
10. [Troubleshooting](#troubleshooting)
11. [Security Considerations](#security-considerations)
12. [Java-Specific Notes](#java-specific-notes)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.18  
**Java Version**: 8 (Amazon Corretto 8 runtime)  
**Build Tool**: Maven  
**Package**: Executable JAR  
**Application Port**: 8080  
**Health Endpoint**: `GET /actuator/health`  
**Target Platform**: AWS EKS (Elastic Kubernetes Service)

ResortsLite is a legacy resort booking application modernised for cloud-native deployment on Amazon EKS. It uses Spring Session backed by Amazon ElastiCache for Redis for distributed session management, and connects to an Oracle database via JDBC.

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker Desktop | 24.x+ | Build and run containers |
| Docker Compose | 2.x+ | Local multi-service orchestration |
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
│       │   ├── SessionConfig.java
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
SPRING_DATASOURCE_PASSWORD=your-password

# Redis / ElastiCache (use localhost for local dev with a local Redis)
SPRING_REDIS_HOST=localhost
SPRING_REDIS_PORT=6379

# Application endpoints
PAYMENT_API_URL=http://payment-service:9090/payments/charge
REPORT_BASE_PATH=/var/reports
BACKUP_PATH=/var/backups/nightly
SERVER_PORT=8080
```

### 2. Start the Application

```bash
# Build and start
docker compose up --build

# Start in background
docker compose up -d --build

# View logs
docker compose logs -f resortsLite

# Stop
docker compose down
```

### 3. Verify the Application

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

The script will prompt you to:
1. Enter an image tag (default: `latest`)
2. Select registry: **1) AWS ECR** or **2) Docker Hub**
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

# Authenticate
aws ecr get-login-password --region $AWS_REGION | \
  docker login --username AWS --password-stdin \
  ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com

# Create repository (first time only)
aws ecr create-repository --repository-name resortsLite --region $AWS_REGION

# Build and push
docker build -t ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/resortsLite:${IMAGE_TAG} .
docker push ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/resortsLite:${IMAGE_TAG}
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
  --name resortsLite-cluster \
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
aws eks update-kubeconfig --region us-east-1 --name resortsLite-cluster
kubectl cluster-info
```

### Step 3: Install AWS Load Balancer Controller

The ingress manifest uses the AWS ALB Ingress Controller. Install it on your EKS cluster:

```bash
# Install cert-manager
kubectl apply --validate=false -f \
  https://github.com/jetstack/cert-manager/releases/download/v1.13.0/cert-manager.yaml

# Install AWS Load Balancer Controller
helm repo add eks https://aws.github.io/eks-charts
helm repo update
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=resortsLite-cluster \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Step 4: Create Kubernetes Secrets

Before deploying, create secrets for sensitive values:

```bash
# Database credentials
kubectl create secret generic resortsLite-db-secret \
  --namespace resortsLite \
  --from-literal=db-username=admin \
  --from-literal=db-password='your-db-password'

# Redis / ElastiCache credentials
kubectl create secret generic resortsLite-redis-secret \
  --namespace resortsLite \
  --from-literal=redis-host=your-elasticache-endpoint.cache.amazonaws.com
```

### Step 5: Run the Deployment Script

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
- AWS region
- EKS cluster name
- Full Docker image URI
- Application environment variables (SPRING_DATASOURCE_URL, PAYMENT_API_URL, etc.)

### Step 6: Verify Deployment

```bash
# Check pods
kubectl get pods -n resortsLite

# Check services
kubectl get svc -n resortsLite

# Check ingress (ALB URL)
kubectl get ingress -n resortsLite

# View pod logs
kubectl logs -f deployment/resortsLite -n resortsLite

# Describe deployment
kubectl describe deployment resortsLite -n resortsLite
```

---

## Kubernetes Manifest Reference

### namespace.yaml
Creates the `resortsLite` namespace to isolate all application resources.

### deployment.yaml
- **Replicas**: 2 (for high availability)
- **Strategy**: RollingUpdate (zero-downtime deployments)
- **Image**: `{{IMAGE_URI}}` — replaced by deploy script
- **Resources**: requests: 250m CPU / 512Mi RAM; limits: 500m CPU / 1Gi RAM
- **Liveness Probe**: `GET /actuator/health` — starts after 60s, every 30s
- **Readiness Probe**: `GET /actuator/health` — starts after 45s, every 15s
- **Volumes**: emptyDir for `/var/reports` and `/var/backups/nightly`

### service.yaml
- **Type**: ClusterIP (internal cluster access only)
- **Port**: 80 → 8080 (container port)

### ingress.yaml
- **Controller**: AWS ALB (Application Load Balancer)
- **Scheme**: internet-facing
- **Health Check**: `/actuator/health`
- **Host**: `resortsLite.example.com` — update to your actual domain

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Default | Source |
|----------|-------------|---------|--------|
| `SPRING_DATASOURCE_URL` | Oracle JDBC connection URL | — | Kubernetes Secret |
| `SPRING_DATASOURCE_USERNAME` | Database username | — | Kubernetes Secret |
| `SPRING_DATASOURCE_PASSWORD` | Database password | — | Kubernetes Secret |
| `SPRING_REDIS_HOST` | ElastiCache Redis endpoint | `localhost` | Kubernetes Secret |
| `SPRING_REDIS_PORT` | Redis port | `6379` | ConfigMap |
| `PAYMENT_API_URL` | Payment service endpoint | cluster DNS | ConfigMap |
| `REPORT_BASE_PATH` | Report file storage path | `/var/reports` | ConfigMap |
| `BACKUP_PATH` | Backup file storage path | `/var/backups/nightly` | ConfigMap |
| `SERVER_PORT` | Application HTTP port | `8080` | ConfigMap |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` | Deployment spec |
| `JAVA_OPTS` | JVM options | see Dockerfile | Deployment spec |

### Update Configuration Without Redeployment

```bash
# Update a ConfigMap value
kubectl patch configmap resortsLite-config -n resortsLite \
  --patch '{"data":{"PAYMENT_API_URL":"http://new-payment-service:9090/charge"}}'

# Restart pods to pick up new config
kubectl rollout restart deployment/resortsLite -n resortsLite
```

---

## Scaling and Management

### Manual Scaling

```bash
# Scale to 4 replicas
kubectl scale deployment resortsLite --replicas=4 -n resortsLite
```

### Horizontal Pod Autoscaler (HPA)

```bash
kubectl autoscale deployment resortsLite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortsLite

# Check HPA status
kubectl get hpa -n resortsLite
```

### Rolling Update

```bash
# Update image
kubectl set image deployment/resortsLite \
  resortsLite=<new-image-uri> \
  -n resortsLite

# Monitor rollout
kubectl rollout status deployment/resortsLite -n resortsLite
```

### Rollback

```bash
# Rollback to previous version
kubectl rollout undo deployment/resortsLite -n resortsLite

# Rollback to specific revision
kubectl rollout history deployment/resortsLite -n resortsLite
kubectl rollout undo deployment/resortsLite --to-revision=2 -n resortsLite
```

---

## Troubleshooting

### Pod Not Starting

```bash
# Check pod status
kubectl get pods -n resortsLite

# Describe pod for events
kubectl describe pod <pod-name> -n resortsLite

# Check logs
kubectl logs <pod-name> -n resortsLite
kubectl logs <pod-name> -n resortsLite --previous  # crashed pod logs
```

### Common Issues

**Issue: `CrashLoopBackOff`**
- Check logs: `kubectl logs <pod-name> -n resortsLite`
- Likely cause: Missing environment variables (DB URL, Redis host)
- Fix: Verify secrets and configmaps are created correctly

**Issue: `ImagePullBackOff`**
- Check ECR permissions: IAM role for EKS node group must have `ecr:GetAuthorizationToken`
- Verify image URI is correct: `kubectl describe pod <pod-name> -n resortsLite`

**Issue: Health check failing**
- JVM startup takes 45-60 seconds — `initialDelaySeconds` is set accordingly
- Check if Oracle DB and Redis are reachable from the pod
- Test manually: `kubectl exec -it <pod-name> -n resortsLite -- wget -qO- http://localhost:8080/actuator/health`

**Issue: Ingress not getting ALB address**
- Verify AWS Load Balancer Controller is installed: `kubectl get pods -n kube-system | grep aws-load-balancer`
- Check ingress events: `kubectl describe ingress resortsLite-ingress -n resortsLite`

**Issue: Redis connection refused**
- Verify `SPRING_REDIS_HOST` secret points to ElastiCache primary endpoint
- Ensure EKS security group allows outbound to ElastiCache security group on port 6379

**Issue: Oracle DB connection timeout**
- Verify `SPRING_DATASOURCE_URL` is correct
- Ensure EKS security group allows outbound to RDS/Oracle security group on port 1521

### Useful Diagnostic Commands

```bash
# Get all resources in namespace
kubectl get all -n resortsLite

# Check resource usage
kubectl top pods -n resortsLite

# Execute shell in running pod
kubectl exec -it <pod-name> -n resortsLite -- /bin/sh

# Port-forward for local testing
kubectl port-forward deployment/resortsLite 8080:8080 -n resortsLite
curl http://localhost:8080/actuator/health
```

---

## Security Considerations

1. **Secrets Management**: Never store credentials in ConfigMaps. Use Kubernetes Secrets or AWS Secrets Manager with the External Secrets Operator.

2. **Non-Root Container**: The Dockerfile creates and uses a non-root `appuser` to reduce attack surface.

3. **Image Scanning**: Enable ECR image scanning to detect vulnerabilities:
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name resortsLite \
     --image-scanning-configuration scanOnPush=true \
     --region us-east-1
   ```

4. **Network Policies**: Consider adding Kubernetes NetworkPolicies to restrict pod-to-pod communication.

5. **RBAC**: Apply least-privilege RBAC roles for the application service account.

6. **TLS**: Update the ingress to use HTTPS with ACM certificates:
   ```yaml
   annotations:
     alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS":443}]'
     alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:us-east-1:123456789:certificate/xxx
   ```

7. **Known Vulnerabilities**: The current codebase includes `log4j-core:2.14.1` (CVE-2021-44228 Log4Shell) and `commons-collections:3.2.1` (CVE-2015-6420). These should be upgraded before production deployment.

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with:
```
-Xms256m        # Initial heap size
-Xmx512m        # Maximum heap size
-XX:+UseContainerSupport    # Respect container memory limits
-XX:MaxRAMPercentage=75.0   # Use 75% of container RAM for heap
-XX:+UseG1GC                # G1 garbage collector (Java 8+)
```

With a 1Gi memory limit, the JVM will use ~768Mi for heap. Adjust `JAVA_OPTS` in the deployment if needed.

### Spring Boot Actuator

The `/actuator/health` endpoint is exposed and used for both liveness and readiness probes. The endpoint returns:
- `UP` — application is healthy
- `DOWN` — application has health issues (triggers pod restart)

### Spring Session + ElastiCache

Sessions are stored in Amazon ElastiCache for Redis, enabling:
- Stateless horizontal scaling (no sticky sessions needed)
- Session persistence across pod restarts
- 30-minute session timeout (configurable via `spring.session.timeout`)

### Spring Profiles

Set `SPRING_PROFILES_ACTIVE=docker` for containerised deployments. Create `application-docker.properties` to override any local development settings.

### Startup Time

Spring Boot with JPA and Redis typically takes 30-60 seconds to start. The Kubernetes probes are configured with appropriate `initialDelaySeconds` (60s liveness, 45s readiness) to accommodate JVM warm-up time.
