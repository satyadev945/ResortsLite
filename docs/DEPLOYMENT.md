# ResortsLite — Deployment Guide

## Overview

This guide covers building, containerizing, and deploying the **ResortsLite** Spring Boot application (Java 8) to **AWS EKS (Elastic Kubernetes Service)**.

- **Framework**: Spring Boot 2.7.x
- **Java Version**: 8 (eclipse-temurin:8-jre runtime)
- **Build Tool**: Maven 3
- **Application Port**: 8080
- **Health Endpoint**: `/actuator/health`
- **Target Platform**: AWS EKS

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker | 20.x+ | Build and run containers |
| Docker Compose | 2.x+ | Local multi-container orchestration |
| Java JDK | 8+ | Local development |
| Maven | 3.8+ | Build tool |

### AWS EKS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS authentication and ECR access |
| kubectl | 1.27+ | Kubernetes cluster management |
| eksctl | 0.150+ | EKS cluster creation (optional) |

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
Comp checking tsst new/
├── Dockerfile                  # Multi-stage Docker build
├── docker-compose.yml          # Local development compose
├── .dockerignore               # Docker build exclusions
├── pom.xml                     # Maven build descriptor
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
│   ├── namespace.yaml
│   ├── deployment.yaml
│   ├── service.yaml
│   └── ingress.yaml
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
# Oracle Database
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@your-db-host:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=your-password

# Redis / ElastiCache
REDIS_HOST=your-redis-host
REDIS_PORT=6379
REDIS_SSL=false

# Application
PAYMENT_SERVICE_URL=http://payment-service:9090/payments/charge
BOOKING_CACHE_TTL_SECONDS=3600
REPORT_BASE_PATH=/reports
BACKUP_PATH=/backups
```

### 2. Build and Start

```bash
# Build and start the application
docker-compose up --build

# Run in background
docker-compose up --build -d

# View logs
docker-compose logs -f resortslite

# Stop
docker-compose down
```

### 3. Verify Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# API endpoint
curl http://localhost:8080/api/bookings/availability?roomType=SUITE
```

---

## Building the Docker Image

### Using Maven Directly

```bash
# Build the JAR
mvn clean package -DskipTests

# Build Docker image
docker build -t resortslite:latest .
```

### Using Build Script (Recommended)

**Linux/macOS:**
```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

**Windows:**
```cmd
scripts\build-push.bat
```

The script will prompt you to:
1. Choose registry (AWS ECR or Docker Hub)
2. Enter registry credentials/details
3. Specify an image tag (defaults to `latest`)

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
  --nodes-max 4
```

**Connect to existing cluster:**
```bash
aws eks update-kubeconfig --region us-east-1 --name your-cluster-name
kubectl cluster-info
```

### Step 3: Install AWS Load Balancer Controller

The ingress uses the AWS ALB Ingress Controller. Install it on your EKS cluster:

```bash
# Add the EKS chart repo
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install the controller
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=your-cluster-name \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Step 4: Build and Push Docker Image to ECR

```bash
./scripts/build-push.sh
# Select: 1 (AWS ECR)
# Enter your AWS Region and Account ID
# Enter image tag (e.g., v1.0.0)
```

### Step 5: Deploy to EKS

**Linux/macOS:**
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
- Full Docker image URI
- Database connection details
- Redis/ElastiCache endpoint
- Other environment variables

### Step 6: Verify Deployment

```bash
# Check pods
kubectl get pods -n resortslite

# Check services
kubectl get svc -n resortslite

# Check ingress (ALB)
kubectl get ingress -n resortslite

# View pod logs
kubectl logs -f deployment/resortslite -n resortslite

# Health check via port-forward
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite
curl http://localhost:8080/actuator/health
```

---

## Kubernetes Manifest Descriptions

### namespace.yaml
Creates the `resortslite` namespace to isolate all application resources.

### deployment.yaml
- **Replicas**: 2 (high availability)
- **Image**: Pulled from ECR/Docker Hub (set via `{{IMAGE_URI}}` placeholder)
- **Resources**: 250m CPU / 512Mi memory (requests), 500m CPU / 1Gi memory (limits)
- **Liveness Probe**: `GET /actuator/health` — restarts unhealthy pods
- **Readiness Probe**: `GET /actuator/health` — removes pod from load balancer until ready
- **Initial Delay**: 60s liveness / 45s readiness (accounts for JVM startup time)

### service.yaml
- **Type**: ClusterIP — internal cluster access only
- **Port**: 80 → 8080 (container port)

### ingress.yaml
- **Controller**: AWS ALB (Application Load Balancer)
- **Scheme**: internet-facing
- **Health Check**: `/actuator/health`
- **SSL Redirect**: HTTP → HTTPS (port 443)

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_DATASOURCE_URL` | Oracle DB JDBC URL | `jdbc:oracle:thin:@localhost:1521:ORCL` |
| `SPRING_DATASOURCE_USERNAME` | DB username | `admin` |
| `SPRING_DATASOURCE_PASSWORD` | DB password | — |
| `REDIS_HOST` | ElastiCache Redis endpoint | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_SSL` | Enable Redis TLS | `false` |
| `BOOKING_CACHE_TTL_SECONDS` | Booking cache TTL | `3600` |
| `PAYMENT_SERVICE_URL` | Payment service endpoint | `http://payment-service:9090/payments/charge` |
| `REPORT_BASE_PATH` | Report file directory | `/reports` |
| `BACKUP_PATH` | Backup file directory | `/backups` |
| `JAVA_OPTS` | JVM options | `-Xmx512m -Xms256m ...` |

### Using Kubernetes Secrets for Sensitive Values

```bash
# Create a secret for database credentials
kubectl create secret generic resortslite-db-secret \
  --from-literal=username=admin \
  --from-literal=password=your-password \
  -n resortslite

# Reference in deployment.yaml
# env:
#   - name: SPRING_DATASOURCE_PASSWORD
#     valueFrom:
#       secretKeyRef:
#         name: resortslite-db-secret
#         key: password
```

---

## Scaling and Management

### Horizontal Pod Autoscaling (HPA)

```bash
# Create HPA
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
# Update image
kubectl set image deployment/resortslite \
  resortslite=<new-image-uri> \
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

**Common causes:**
- `CrashLoopBackOff`: Application failing to start — check DB/Redis connectivity
- `ImagePullBackOff`: ECR authentication issue — verify IAM role for node group
- `OOMKilled`: Increase memory limits in deployment.yaml

### JVM Startup Issues

Spring Boot with Java 8 may take 30–60 seconds to start. If liveness probes fail:

```yaml
# Increase initialDelaySeconds in deployment.yaml
livenessProbe:
  initialDelaySeconds: 90   # Increase from 60
readinessProbe:
  initialDelaySeconds: 60   # Increase from 45
```

### Database Connection Issues

```bash
# Test DB connectivity from pod
kubectl exec -it deployment/resortslite -n resortslite -- \
  sh -c "nc -zv your-db-host 1521"
```

### Redis Connection Issues

```bash
# Test Redis connectivity from pod
kubectl exec -it deployment/resortslite -n resortslite -- \
  sh -c "nc -zv your-redis-host 6379"
```

### Ingress / ALB Not Provisioning

```bash
# Check ALB controller logs
kubectl logs -n kube-system deployment/aws-load-balancer-controller

# Verify ingress annotations
kubectl describe ingress resortslite-ingress -n resortslite
```

---

## Security Considerations

1. **Secrets Management**: Store database passwords and Redis credentials in AWS Secrets Manager or Kubernetes Secrets — never in plain ConfigMaps or environment variables in production.

2. **IRSA (IAM Roles for Service Accounts)**: Use IRSA for pod-level AWS permissions (ElastiCache, Secrets Manager) instead of node-level IAM roles.

3. **Network Policies**: Restrict pod-to-pod communication using Kubernetes NetworkPolicy.

4. **Image Scanning**: Enable ECR image scanning to detect vulnerabilities in the container image.

5. **TLS**: Ensure the ALB terminates TLS and all internal traffic uses HTTPS where possible.

6. **Non-Root User**: The Dockerfile runs the application as a non-root user (`appuser`) for security.

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with:
```
-Xmx512m -Xms256m
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0
-XX:+UnlockExperimentalVMOptions
```

`UseContainerSupport` ensures the JVM respects container memory limits (available in Java 8u191+).

### Spring Boot Actuator

Health endpoints are exposed at:
- **Liveness**: `GET /actuator/health`
- **Info**: `GET /actuator/info`

Management endpoints are configured in `application.properties`:
```properties
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=always
management.endpoints.web.base-path=/actuator
```

### Spring Session with Redis

The application uses Spring Session backed by Amazon ElastiCache (Redis) for distributed session management across EKS pod replicas. Ensure the ElastiCache cluster is accessible from the EKS node group's security group.

---

*Generated for ResortsLite — Spring Boot 2.7.x / Java 8 / AWS EKS*
