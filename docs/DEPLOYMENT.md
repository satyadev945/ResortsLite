# ResortsLite — Deployment Guide (AWS EKS)

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Build and Push Docker Image](#build-and-push-docker-image)
6. [AWS EKS Deployment](#aws-eks-deployment)
7. [Configuration Management](#configuration-management)
8. [Kubernetes Manifest Reference](#kubernetes-manifest-reference)
9. [Scaling and Management](#scaling-and-management)
10. [Troubleshooting](#troubleshooting)
11. [Security Considerations](#security-considerations)
12. [Java-Specific Notes](#java-specific-notes)

---

## Overview

**ResortsLite** is a Java 8 / Spring Boot 2.7.x resort booking application. It exposes a REST API for booking management and uses:
- **Oracle Database** for persistent booking data
- **Amazon ElastiCache for Redis** for distributed session management and booking cache
- **Spring Boot Actuator** (`/actuator/health`) for Kubernetes liveness and readiness probes

Target deployment platform: **AWS EKS (Elastic Kubernetes Service)**

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker | 20.10+ | Build and run containers |
| Docker Compose | 2.x | Local multi-container orchestration |
| Java JDK | 8 | Local development (optional) |
| Maven | 3.8+ | Local build (optional) |

### AWS EKS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS authentication and ECR operations |
| kubectl | 1.27+ | Kubernetes cluster management |
| eksctl | 0.150+ | EKS cluster creation (optional) |

### IAM Permissions Required
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
Container-Resor/
├── Dockerfile                    # Multi-stage Docker build
├── docker-compose.yml            # Local development compose file
├── .dockerignore                 # Docker build exclusions
├── pom.xml                       # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java   # Spring Boot entry point
│       │   ├── BookingController.java         # REST API controller
│       │   ├── BookingService.java            # Business logic
│       │   ├── ReportService.java             # Report generation
│       │   └── RedisConfig.java               # Redis/ElastiCache config
│       └── resources/
│           └── application.properties         # Application configuration
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
SPRING_DATASOURCE_PASSWORD=your-secure-password

# Redis / ElastiCache
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_SSL=false

# Internal service endpoints
PAYMENT_API_URL=http://payment-service.payments.svc.cluster.local:9090/payments/charge

# File paths
REPORTS_BASE_PATH=/mnt/reports
BACKUP_PATH=/mnt/backups
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

# Authenticate to ECR
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
aws eks update-kubeconfig --region us-east-1 --name <your-cluster-name>
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
  --set clusterName=<your-cluster-name> \
  --set serviceAccountAnnotations."eks\.amazonaws\.com/role-arn"=<IRSA_ROLE_ARN>
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
- AWS Region
- EKS Cluster Name
- Docker image URI (full path with tag)
- Oracle database connection details
- Redis / ElastiCache connection details
- Payment API URL

### Step 5: Verify Deployment

```bash
# Check all resources
kubectl get all -n resortslite

# Check pod logs
kubectl logs -l app=resortslite -n resortslite --tail=100

# Check ingress
kubectl describe ingress resortslite-ingress -n resortslite

# Health check via port-forward
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite
curl http://localhost:8080/actuator/health
```

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` |
| `JAVA_OPTS` | JVM options | `-Xmx512m -Xms256m ...` |
| `SPRING_DATASOURCE_URL` | Oracle JDBC URL | — |
| `SPRING_DATASOURCE_USERNAME` | DB username | `admin` |
| `SPRING_DATASOURCE_PASSWORD` | DB password | — |
| `REDIS_HOST` | Redis/ElastiCache host | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis password | — |
| `REDIS_SSL` | Enable Redis TLS | `false` |
| `PAYMENT_API_URL` | Payment service URL | cluster DNS |
| `REPORTS_BASE_PATH` | Reports mount path | `/mnt/reports` |
| `BACKUP_PATH` | Backup mount path | `/mnt/backups` |

### Using Kubernetes Secrets for Sensitive Values

```bash
# Create a secret for database credentials
kubectl create secret generic resortslite-db-secret \
  --from-literal=username=admin \
  --from-literal=password=your-secure-password \
  -n resortslite

# Create a secret for Redis password
kubectl create secret generic resortslite-redis-secret \
  --from-literal=password=your-redis-password \
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

## Kubernetes Manifest Reference

### namespace.yaml
Creates the `resortslite` namespace to isolate all application resources.

### deployment.yaml
- **Replicas**: 2 (for high availability)
- **Image**: Pulled from `{{IMAGE_URI}}` (replaced at deploy time)
- **Resources**: 250m CPU / 512Mi memory (requests); 500m CPU / 1Gi memory (limits)
- **Liveness Probe**: `GET /actuator/health` — starts after 60s, every 30s
- **Readiness Probe**: `GET /actuator/health` — starts after 30s, every 15s
- **Volumes**: `emptyDir` for `/mnt/reports` and `/mnt/backups` (replace with PVC for production)

### service.yaml
- **Type**: `ClusterIP` — internal cluster access only
- **Port**: 80 → 8080 (container port)

### ingress.yaml
- **Class**: `alb` (AWS Load Balancer Controller)
- **Scheme**: `internet-facing`
- **Host**: `resortslite.example.com` (update to your actual domain)
- **Health check path**: `/actuator/health`

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

### Scale Manually

```bash
kubectl scale deployment resortslite --replicas=4 -n resortslite
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
- `ImagePullBackOff`: ECR authentication issue or wrong image URI
- `CrashLoopBackOff`: Application startup failure — check logs for DB/Redis connection errors
- `OOMKilled`: Increase memory limits in `deployment.yaml`

### Database Connection Issues

```bash
# Verify datasource URL is correct
kubectl exec -it <pod-name> -n resortslite -- env | grep SPRING_DATASOURCE

# Check Oracle connectivity from pod
kubectl exec -it <pod-name> -n resortslite -- sh -c "nc -zv <db-host> 1521"
```

### Redis / ElastiCache Connection Issues

```bash
# Verify Redis env vars
kubectl exec -it <pod-name> -n resortslite -- env | grep REDIS

# Test Redis connectivity
kubectl exec -it <pod-name> -n resortslite -- sh -c "nc -zv $REDIS_HOST $REDIS_PORT"
```

### Ingress / ALB Issues

```bash
# Check ingress status
kubectl describe ingress resortslite-ingress -n resortslite

# Verify AWS Load Balancer Controller is running
kubectl get pods -n kube-system | grep aws-load-balancer-controller

# Check controller logs
kubectl logs -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller
```

### Health Check Failures

```bash
# Port-forward and test health endpoint directly
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite
curl -v http://localhost:8080/actuator/health
```

---

## Security Considerations

1. **Never commit secrets**: Use Kubernetes Secrets or AWS Secrets Manager for credentials
2. **Use IRSA**: Configure IAM Roles for Service Accounts for credential-free access to AWS services (RDS, ElastiCache, S3)
3. **Enable Redis TLS**: Set `REDIS_SSL=true` for ElastiCache in production
4. **Network Policies**: Restrict pod-to-pod communication with Kubernetes NetworkPolicy
5. **Image Scanning**: Enable ECR image scanning to detect vulnerabilities
6. **Non-root container**: The Dockerfile runs as a non-root `appuser` — do not override `runAsUser`
7. **Secrets rotation**: Rotate database and Redis passwords regularly using AWS Secrets Manager rotation

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with:
```
-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0
```

- `-XX:+UseContainerSupport`: Enables JVM container awareness (reads cgroup limits)
- `-XX:MaxRAMPercentage=75.0`: JVM uses up to 75% of container memory limit
- Adjust `JAVA_OPTS` in `deployment.yaml` if you change memory limits

### Spring Boot Actuator

Health endpoint is exposed at `/actuator/health`. The `application.properties` configures:
```properties
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=always
```

### Spring Session with Redis

Session state is stored in Amazon ElastiCache for Redis via Spring Session. This enables:
- Horizontal scaling across multiple pod replicas
- Session persistence across container restarts
- Shared session state behind AWS ALB

Ensure `REDIS_HOST` points to your ElastiCache cluster endpoint.

### Oracle Database

The application uses Oracle JDBC (`ojdbc8`). Ensure:
- The EKS worker nodes can reach the Oracle DB endpoint (VPC security groups / NACLs)
- The JDBC URL format: `jdbc:oracle:thin:@<host>:<port>:<SID>` or `jdbc:oracle:thin:@//<host>:<port>/<service>`

### Java 8 Base Image

The runtime image uses `amazoncorretto:8` (Amazon Corretto — Amazon's distribution of OpenJDK 8). This provides:
- Long-term support from AWS
- Security patches and performance improvements
- Optimized for AWS environments
