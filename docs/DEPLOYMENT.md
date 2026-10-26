# ResortsLite — Deployment Guide (AWS EKS)

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Building and Pushing the Docker Image](#building-and-pushing-the-docker-image)
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
**Java Version**: Java 8  
**Build Tool**: Maven  
**Target Platform**: AWS EKS (Elastic Kubernetes Service)  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

ResortsLite is a legacy resort booking application modernized for cloud-native deployment on AWS EKS. It uses Spring Session backed by Amazon ElastiCache (Redis) for stateless horizontal scaling, and connects to an Oracle database for persistent storage.

---

## Prerequisites

### Local Development
- Docker Desktop 24.x or later
- Docker Compose v2.x or later
- Java 8 JDK (for local builds outside Docker)
- Maven 3.8.x or later

### AWS EKS Deployment
- **AWS CLI** v2.x — [Install guide](https://docs.aws.amazon.com/cli/latest/userguide/install-cliv2.html)
- **kubectl** v1.27+ — [Install guide](https://kubernetes.io/docs/tasks/tools/)
- **eksctl** (optional, for cluster creation) — [Install guide](https://eksctl.io/)
- AWS IAM permissions:
  - `eks:DescribeCluster`, `eks:UpdateKubeconfig`
  - `ecr:GetAuthorizationToken`, `ecr:BatchCheckLayerAvailability`, `ecr:PutImage`
  - `ecr:CreateRepository`, `ecr:DescribeRepositories`
- An existing EKS cluster with:
  - AWS Load Balancer Controller installed (for ALB Ingress)
  - IRSA (IAM Roles for Service Accounts) configured if accessing ElastiCache/RDS

---

## Project Structure

```
New tetsing check 7339/
├── Dockerfile                  # Multi-stage Docker build (Java 8)
├── .dockerignore               # Excludes build artifacts and wrapper files
├── docker-compose.yml          # Local development compose (app only)
├── pom.xml                     # Maven build descriptor
├── src/                        # Application source code
├── kubernetes/
│   ├── namespace.yaml          # Kubernetes namespace
│   ├── deployment.yaml         # Deployment with 2 replicas
│   ├── service.yaml            # ClusterIP service
│   └── ingress.yaml            # ALB Ingress
├── scripts/
│   ├── build-push.sh           # Linux/macOS: build & push image
│   ├── build-push.bat          # Windows: build & push image
│   ├── deploy-image.sh         # Linux/macOS: deploy to EKS
│   └── deploy-image.bat        # Windows: deploy to EKS
└── docs/
    └── DEPLOYMENT.md           # This file
```

---

## Local Development with Docker Compose

### 1. Configure Environment Variables

Create a `.env` file in the project root (never commit this file):

```env
# Oracle Database
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=your_db_password

# Redis / ElastiCache
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_SSL=false

# Internal service endpoints
PAYMENT_API_URL=http://payment-service:9090/payments/charge

# Report storage
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
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=STANDARD&checkIn=2024-01-15&checkOut=2024-01-20"
```

---

## Building and Pushing the Docker Image

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
# Authenticate
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin \
  123456789012.dkr.ecr.us-east-1.amazonaws.com

# Create repository (first time only)
aws ecr create-repository --repository-name resortslite --region us-east-1

# Build
docker build -t 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest .

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

### Step 2: Verify EKS Cluster Access

```bash
aws eks update-kubeconfig --region us-east-1 --name your-cluster-name
kubectl cluster-info
kubectl get nodes
```

### Step 3: Install AWS Load Balancer Controller (if not installed)

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
- AWS Region and EKS Cluster Name
- Full Docker image URI (e.g., `123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest`)
- Application environment variables (Oracle DB, Redis, service endpoints)

### Step 5: Verify Deployment

```bash
# Check pods
kubectl get pods -n resortslite

# Check services
kubectl get svc -n resortslite

# Check ingress (ALB URL)
kubectl get ingress -n resortslite

# View pod logs
kubectl logs -f deployment/resortslite -n resortslite

# Describe deployment
kubectl describe deployment resortslite -n resortslite
```

---

## Kubernetes Manifest Reference

### namespace.yaml
Creates the `resortslite` Kubernetes namespace to isolate all application resources.

### deployment.yaml
- **Replicas**: 2 (horizontal scaling)
- **Image**: `{{IMAGE_URI}}` — replaced by deploy script
- **Resources**: requests `250m CPU / 512Mi RAM`, limits `500m CPU / 1Gi RAM`
- **Liveness Probe**: `GET /actuator/health` — starts after 60s, every 30s
- **Readiness Probe**: `GET /actuator/health` — starts after 30s, every 15s
- **Security**: runs as non-root user (UID 1000)
- **Graceful Shutdown**: 60-second termination grace period

### service.yaml
- **Type**: ClusterIP (internal cluster access)
- **Port**: 80 → 8080 (container port)

### ingress.yaml
- **Controller**: AWS ALB (Application Load Balancer)
- **Scheme**: internet-facing
- **Health Check**: `/actuator/health`
- **Host**: `resortslite.example.com` — update to your actual domain

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Default |
|---|---|---|
| `SPRING_DATASOURCE_URL` | Oracle JDBC connection URL | `jdbc:oracle:thin:@localhost:1521:ORCL` |
| `SPRING_DATASOURCE_USERNAME` | Database username | `admin` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | — |
| `REDIS_HOST` | ElastiCache Redis endpoint | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis auth token | — |
| `REDIS_SSL` | Enable TLS for Redis | `false` |
| `PAYMENT_API_URL` | Payment service endpoint | `http://payment-service:9090/payments/charge` |
| `REPORT_BASE_PATH` | Report file storage path | `/var/reports` |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` |
| `JAVA_OPTS` | JVM options | `-Xmx512m -Xms256m ...` |

### Using Kubernetes Secrets (Recommended)

```bash
# Create secret for database credentials
kubectl create secret generic resortslite-db-secret \
  --from-literal=username=admin \
  --from-literal=password=your_db_password \
  -n resortslite

# Create secret for Redis
kubectl create secret generic resortslite-redis-secret \
  --from-literal=password=your_redis_password \
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

### Using AWS Secrets Manager with IRSA

```bash
# Store secret
aws secretsmanager create-secret \
  --name resortslite/db-credentials \
  --secret-string '{"username":"admin","password":"your_password"}'

# Use External Secrets Operator to sync to Kubernetes Secrets
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

# Check logs
kubectl logs <pod-name> -n resortslite
kubectl logs <pod-name> -n resortslite --previous  # crashed pod
```

### Common Issues

**OOMKilled (Out of Memory)**
```bash
# Increase memory limits in deployment.yaml
resources:
  limits:
    memory: "2Gi"
```

**CrashLoopBackOff**
- Check application logs for startup errors
- Verify database and Redis connectivity
- Ensure environment variables are correctly set
- JVM startup for Java 8 apps can take 30-60 seconds — check `initialDelaySeconds`

**Readiness Probe Failing**
```bash
# Check if actuator is accessible
kubectl exec -it <pod-name> -n resortslite -- \
  wget -qO- http://localhost:8080/actuator/health
```

**Database Connection Issues**
```bash
# Verify Oracle JDBC URL format
# jdbc:oracle:thin:@<host>:<port>:<sid>
# or
# jdbc:oracle:thin:@//<host>:<port>/<service_name>

# Check security groups allow EKS nodes to reach Oracle DB on port 1521
```

**Redis Connection Issues**
```bash
# Verify ElastiCache security group allows EKS nodes on port 6379
# Check REDIS_HOST points to ElastiCache primary endpoint
# For TLS: set REDIS_SSL=true and use port 6380
```

**ALB Ingress Not Getting Address**
```bash
# Verify AWS Load Balancer Controller is running
kubectl get pods -n kube-system | grep aws-load-balancer

# Check ingress events
kubectl describe ingress resortslite-ingress -n resortslite
```

---

## Security Considerations

1. **Never hardcode credentials** — use Kubernetes Secrets or AWS Secrets Manager
2. **Use IRSA** for pod-level AWS permissions (ElastiCache, RDS, Secrets Manager)
3. **Enable Redis TLS** (`REDIS_SSL=true`) for ElastiCache in production
4. **Use HTTPS** for the ALB Ingress — add ACM certificate annotation:
   ```yaml
   alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:us-east-1:123456789012:certificate/xxx
   alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS":443}]'
   ```
5. **Network Policies** — restrict pod-to-pod communication
6. **Image scanning** — enable ECR image scanning on push
7. **Non-root container** — already configured (UID 1000)
8. **Update vulnerable dependencies** — log4j 2.14.1 (CVE-2021-44228) and commons-collections 3.2.1 (CVE-2015-6420) must be upgraded before production deployment

---

## Java-Specific Notes

### JVM Memory Configuration

The Dockerfile and deployment set these JVM flags:
```
-Xmx512m              # Maximum heap size
-Xms256m              # Initial heap size
-XX:+UseContainerSupport    # Respect container memory limits
-XX:MaxRAMPercentage=75.0   # Use 75% of container RAM for heap
```

Adjust `JAVA_OPTS` in `deployment.yaml` based on your workload.

### Spring Boot Actuator

Health endpoint is exposed at `/actuator/health`. The `application.properties` configures:
```properties
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=always
```

### Spring Session with Redis

The application uses `@EnableRedisHttpSession` to store HTTP sessions in Amazon ElastiCache (Redis). This enables stateless pods and horizontal scaling without sticky sessions. Ensure `REDIS_HOST` points to the ElastiCache primary endpoint.

### Graceful Shutdown

The deployment is configured with `terminationGracePeriodSeconds: 60`. Spring Boot 2.7.x supports graceful shutdown via:
```properties
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=50s
```

### Java 8 Startup Time

Java 8 applications typically take 20-45 seconds to start. The liveness probe `initialDelaySeconds: 60` accounts for this. If your application takes longer, increase this value.
