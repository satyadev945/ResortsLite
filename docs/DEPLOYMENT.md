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
**Framework**: Spring Boot 2.7.x  
**Java Version**: 8  
**Build Tool**: Maven  
**Target Platform**: AWS EKS (Elastic Kubernetes Service)  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

ResortsLite is a legacy resort booking REST API modernised for cloud-native deployment on AWS EKS. It uses Spring Session backed by Amazon ElastiCache (Redis) for distributed session management and connects to an Oracle database.

---

## Prerequisites

### Local Development
- Docker Desktop 24.x or later
- Docker Compose v2.x or later
- Java 8 JDK (for local builds outside Docker)
- Maven 3.8.x or later

### AWS EKS Deployment
- AWS CLI v2 configured with appropriate IAM permissions
- `kubectl` v1.27 or later
- `eksctl` (optional, for cluster creation)
- An existing EKS cluster with the **AWS Load Balancer Controller** installed
- Amazon ECR repository (auto-created by `build-push.sh`)
- Amazon ElastiCache (Redis) cluster accessible from EKS pods
- Oracle database accessible from EKS pods

### Required IAM Permissions
```
ecr:GetAuthorizationToken
ecr:BatchCheckLayerAvailability
ecr:GetDownloadUrlForLayer
ecr:BatchGetImage
ecr:PutImage
ecr:InitiateLayerUpload
ecr:UploadLayerPart
ecr:CompleteLayerUpload
ecr:CreateRepository
ecr:DescribeRepositories
eks:DescribeCluster
eks:ListClusters
```

---

## Project Structure

```
TEST/
├── Dockerfile                  # Multi-stage build (Java 8 / Spring Boot)
├── docker-compose.yml          # Local development (app only)
├── .dockerignore               # Excludes build artefacts and wrapper files
├── pom.xml                     # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml          # Kubernetes namespace
│   ├── deployment.yaml         # Application deployment (2 replicas)
│   ├── service.yaml            # ClusterIP service
│   └── ingress.yaml            # AWS ALB ingress
├── scripts/
│   ├── build-push.sh           # Linux/macOS build & push
│   ├── build-push.bat          # Windows build & push
│   ├── deploy-image.sh         # Linux/macOS EKS deploy
│   └── deploy-image.bat        # Windows EKS deploy
└── docs/
    └── DEPLOYMENT.md           # This file
```

---

## Local Development with Docker Compose

### 1. Configure Environment Variables

Create a `.env` file in the project root (never commit this file):

```env
# Redis
REDIS_HOST=your-elasticache-endpoint.cache.amazonaws.com
REDIS_PORT=6379

# Oracle Database
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@your-db-host:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=your-secure-password

# Service Endpoints
PAYMENT_API_URL=http://payment-svc:9090/payments/charge
APP_PAYMENT_ENDPOINT=http://payment-svc:9090/charge
APP_INVENTORY_ENDPOINT=http://inventory-svc:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://notify-svc:7070/send

# Report Storage
REPORT_BASE_PATH=/var/reports
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
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=STANDARD&checkIn=2024-06-01&checkOut=2024-06-05"
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

### Manual Build

```bash
# Build image
docker build -t resortslite:latest .

# Tag for ECR
docker tag resortslite:latest 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest

# Login to ECR
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin 123456789012.dkr.ecr.us-east-1.amazonaws.com

# Push
docker push 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
```

---

## AWS EKS Deployment

### Step 1: Verify EKS Cluster Access

```bash
aws eks update-kubeconfig --region us-east-1 --name your-cluster-name
kubectl cluster-info
kubectl get nodes
```

### Step 2: Install AWS Load Balancer Controller (if not already installed)

```bash
# Add Helm repo
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install controller
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=your-cluster-name \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Step 3: Run the Deployment Script

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

The script will prompt for:
- AWS Region
- EKS Cluster Name
- Full Docker image URI (e.g., `123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest`)
- All application environment variables (Redis, Oracle DB, service endpoints)

### Step 4: Verify Deployment

```bash
# Check pods
kubectl get pods -n resortslite

# Check services
kubectl get svc -n resortslite

# Check ingress (wait for ALB provisioning ~2-3 minutes)
kubectl get ingress -n resortslite

# View pod logs
kubectl logs -f deployment/resortslite -n resortslite

# Describe deployment
kubectl describe deployment resortslite -n resortslite
```

### Step 5: Access the Application

```bash
# Get ALB hostname
kubectl get ingress resortslite-ingress -n resortslite \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'

# Health check
curl http://<ALB_HOSTNAME>/actuator/health
```

---

## Kubernetes Manifest Reference

### namespace.yaml
Creates the `resortslite` Kubernetes namespace to isolate all application resources.

### deployment.yaml
- **Replicas**: 2 (for high availability)
- **Image**: Placeholder `{{IMAGE_URI}}` replaced by deploy script
- **Resources**: requests `250m CPU / 512Mi RAM`, limits `500m CPU / 1Gi RAM`
- **Liveness Probe**: `GET /actuator/health` — initial delay 60s, period 30s
- **Readiness Probe**: `GET /actuator/health` — initial delay 30s, period 15s
- **Environment Variables**: All externalised via placeholders replaced at deploy time

### service.yaml
- **Type**: ClusterIP (internal cluster access only)
- **Port**: 80 → 8080 (container port)

### ingress.yaml
- **Class**: AWS ALB (Application Load Balancer)
- **Scheme**: internet-facing
- **Health Check Path**: `/actuator/health`
- **Host**: `resortslite.example.com` (update to your actual domain)

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Default |
|---|---|---|
| `REDIS_HOST` | ElastiCache primary endpoint | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `SPRING_DATASOURCE_URL` | Oracle JDBC URL | `jdbc:oracle:thin:@localhost:1521:ORCL` |
| `SPRING_DATASOURCE_USERNAME` | Database username | `admin` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | — |
| `PAYMENT_API_URL` | Payment service URL | cluster-local URL |
| `APP_PAYMENT_ENDPOINT` | Payment endpoint | internal URL |
| `APP_INVENTORY_ENDPOINT` | Inventory service URL | internal URL |
| `APP_NOTIFICATION_ENDPOINT` | Notification service URL | internal URL |
| `REPORT_BASE_PATH` | Report file storage path | `/var/reports` |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` |
| `JAVA_OPTS` | JVM options | `-Xms256m -Xmx512m ...` |

### Using Kubernetes Secrets for Sensitive Values

```bash
# Create secret for database credentials
kubectl create secret generic resortslite-db-secret \
  --from-literal=username=admin \
  --from-literal=password=your-secure-password \
  -n resortslite

# Reference in deployment.yaml
# env:
#   - name: SPRING_DATASOURCE_PASSWORD
#     valueFrom:
#       secretKeyRef:
#         name: resortslite-db-secret
#         key: password
```

### Using AWS Secrets Manager with EKS

```bash
# Install Secrets Store CSI Driver
helm repo add secrets-store-csi-driver \
  https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts
helm install csi-secrets-store \
  secrets-store-csi-driver/secrets-store-csi-driver \
  -n kube-system
```

---

## Scaling and Management

### Manual Scaling

```bash
# Scale to 4 replicas
kubectl scale deployment resortslite --replicas=4 -n resortslite
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
# Check pod status
kubectl get pods -n resortslite

# Describe pod for events
kubectl describe pod <pod-name> -n resortslite

# View container logs
kubectl logs <pod-name> -n resortslite

# View previous container logs (if crashed)
kubectl logs <pod-name> -n resortslite --previous
```

### Common Issues

**1. OOMKilled (Out of Memory)**
```bash
# Increase memory limits in deployment.yaml
# limits.memory: "2Gi"
# Also increase JAVA_OPTS: -Xmx1536m
```

**2. Redis Connection Refused**
```bash
# Verify REDIS_HOST is correct ElastiCache endpoint
kubectl exec -it <pod-name> -n resortslite -- env | grep REDIS

# Check ElastiCache security group allows EKS node group
```

**3. Oracle Database Connection Timeout**
```bash
# Verify SPRING_DATASOURCE_URL is reachable from EKS
kubectl exec -it <pod-name> -n resortslite -- env | grep DATASOURCE

# Check Oracle security group / VPC peering
```

**4. Ingress Not Getting ALB Hostname**
```bash
# Verify AWS Load Balancer Controller is running
kubectl get pods -n kube-system | grep aws-load-balancer

# Check ingress events
kubectl describe ingress resortslite-ingress -n resortslite
```

**5. Health Check Failing**
```bash
# Test health endpoint directly
kubectl port-forward deployment/resortslite 8080:8080 -n resortslite
curl http://localhost:8080/actuator/health

# Check if Redis is reachable (Spring Session requires Redis)
```

**6. JVM Startup Slow**
The liveness probe has `initialDelaySeconds: 60` to accommodate JVM startup. If pods are being killed before startup completes, increase this value in `deployment.yaml`.

---

## Security Considerations

1. **Never commit secrets** to source control — use Kubernetes Secrets or AWS Secrets Manager
2. **Use IRSA** (IAM Roles for Service Accounts) for AWS service access from pods
3. **Enable network policies** to restrict pod-to-pod communication
4. **Scan images** with Amazon ECR image scanning or Trivy before deployment
5. **Rotate credentials** regularly — database passwords, Redis auth tokens
6. **Use HTTPS** — configure TLS termination at the ALB level
7. **Restrict actuator endpoints** — in production, expose only `/actuator/health`

```properties
# Restrict actuator in production
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=when-authorized
```

---

## Java-Specific Notes

### JVM Container Optimisations

The Dockerfile and deployment include these JVM flags:
```
-Xms256m                    # Initial heap size
-Xmx512m                    # Maximum heap size
-XX:+UseContainerSupport    # Respect container CPU/memory limits
-XX:MaxRAMPercentage=75.0   # Use 75% of container memory for heap
-Djava.security.egd=file:/dev/./urandom  # Faster random number generation
```

### Spring Boot Actuator

Health endpoint is available at:
- `GET /actuator/health` — liveness and readiness probe target

### Spring Session + Redis

This application uses Spring Session Data Redis for distributed session management. All pods share session state via Amazon ElastiCache. Ensure:
- ElastiCache cluster is in the same VPC as EKS
- Security groups allow port 6379 from EKS node group
- `REDIS_HOST` points to the ElastiCache primary endpoint

### Spring Profiles

Set `SPRING_PROFILES_ACTIVE=docker` (default in Dockerfile) to activate cloud-native configuration. Create `application-docker.properties` for environment-specific overrides.

### Graceful Shutdown

The deployment uses `terminationGracePeriodSeconds: 30`. Spring Boot 2.7.x supports graceful shutdown — add to `application.properties`:
```properties
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=20s
```
