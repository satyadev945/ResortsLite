# ResortsLite — Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Analysis](#project-analysis)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Build and Push Docker Image](#build-and-push-docker-image)
6. [AWS EKS Deployment](#aws-eks-deployment)
7. [Kubernetes Manifest Reference](#kubernetes-manifest-reference)
8. [Configuration Management](#configuration-management)
9. [Scaling and Management](#scaling-and-management)
10. [Troubleshooting](#troubleshooting)
11. [Security Considerations](#security-considerations)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.x  
**Java Version**: 8  
**Build Tool**: Maven  
**Target Platform**: AWS EKS (Elastic Kubernetes Service)  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

ResortsLite is a legacy resort booking application modernised for cloud-native deployment on AWS EKS. It uses Amazon ElastiCache (Redis) for distributed HTTP sessions and booking cache, and Oracle Database as its persistence layer.

---

## Prerequisites

### Local Development
| Tool | Minimum Version | Purpose |
|------|----------------|---------|
| Docker Desktop | 24.x | Build and run containers |
| Docker Compose | 2.x | Local multi-service orchestration |
| Java JDK | 8 | Local builds (optional) |
| Maven | 3.8+ | Local builds (optional) |

### AWS EKS Deployment
| Tool | Minimum Version | Purpose |
|------|----------------|---------|
| AWS CLI | 2.x | AWS authentication and ECR operations |
| kubectl | 1.27+ | Kubernetes cluster management |
| eksctl | 0.160+ | EKS cluster provisioning (optional) |
| Docker | 24.x | Image build and push |

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

## Project Analysis

| Property | Value |
|----------|-------|
| Artifact ID | resortsLite |
| Group ID | com.demo |
| Spring Boot | 2.7.18 |
| Java | 8 |
| Packaging | JAR |
| Application Port | 8080 |
| Management Port | 8080 (same, `/actuator` base path) |
| Health Endpoint | `/actuator/health` |
| Session Store | Amazon ElastiCache (Redis) via Spring Session |
| Cache | Amazon ElastiCache (Redis) via RedisTemplate |
| Database | Oracle (ojdbc8) |
| Builder Image | `maven:3.8.6-openjdk-8-slim` |
| Runtime Image | `eclipse-temurin:8-jre` (explicit) |

---

## Local Development with Docker Compose

### 1. Configure Environment Variables

Create a `.env` file in the project root (never commit this file):

```bash
# Oracle DataSource
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@<your-oracle-host>:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=<your-password>

# Amazon ElastiCache (Redis) — use localhost for local Redis
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=

# Internal service endpoints
APP_PAYMENT_ENDPOINT=http://payment-svc:9090/charge
APP_INVENTORY_ENDPOINT=http://inventory-svc:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://notify-svc:7070/send

# File paths
REPORT_BASE_PATH=/var/reports
BACKUP_PATH=/var/backups/nightly

# Cache TTL
BOOKING_CACHE_TTL_SECONDS=3600
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
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=Suite&checkIn=2024-06-01&checkOut=2024-06-05"
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
2. Select registry type (AWS ECR or Docker Hub)
3. Provide registry credentials

### Windows

```cmd
scripts\build-push.bat
```

### Manual Build (Advanced)

```bash
# Build image
docker build -t resortslite:latest .

# Tag for ECR
docker tag resortslite:latest <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/resortslite:latest

# Authenticate with ECR
aws ecr get-login-password --region <REGION> | \
  docker login --username AWS --password-stdin <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com

# Push
docker push <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/resortslite:latest
```

---

## AWS EKS Deployment

### Step 1: Create or Connect to an EKS Cluster

```bash
# Create a new cluster (if needed)
eksctl create cluster \
  --name resortslite-cluster \
  --region us-east-1 \
  --nodegroup-name standard-workers \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 4

# Configure kubectl for existing cluster
aws eks update-kubeconfig --region us-east-1 --name resortslite-cluster

# Verify connectivity
kubectl cluster-info
kubectl get nodes
```

### Step 2: Install AWS Load Balancer Controller

The Ingress manifest uses the AWS Load Balancer Controller (ALB). Install it if not already present:

```bash
# Add the EKS chart repository
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install the controller
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=resortslite-cluster \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Step 3: Run the Deployment Script

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

The script will prompt for:
- AWS region and EKS cluster name
- Full Docker image URI (e.g., `123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest`)
- All application environment variables (Oracle DB, Redis, service endpoints, etc.)

### Step 4: Verify Deployment

```bash
# Check pods
kubectl get pods -n resortslite

# Check services
kubectl get svc -n resortslite

# Check ingress (wait for ALB to provision)
kubectl get ingress -n resortslite

# View pod logs
kubectl logs -f deployment/resortslite -n resortslite

# Health check via port-forward (before ingress is ready)
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite
curl http://localhost:8080/actuator/health
```

### Step 5: Access the Application

Once the ALB is provisioned (may take 2–5 minutes):

```bash
# Get the ALB hostname
kubectl get ingress resortslite-ingress -n resortslite \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
```

Access the application at: `http://<ALB_HOSTNAME>/api/bookings/...`

---

## Kubernetes Manifest Reference

### namespace.yaml
Creates the `resortslite` namespace to isolate all application resources.

### deployment.yaml
- **Replicas**: 2 (for high availability)
- **Image**: Pulled from ECR (placeholder `{{IMAGE_URI}}` replaced at deploy time)
- **Resources**: 250m CPU / 512Mi memory (requests); 500m CPU / 1Gi memory (limits)
- **Liveness Probe**: `GET /actuator/health` — initial delay 60s (JVM warm-up), period 30s
- **Readiness Probe**: `GET /actuator/health` — initial delay 30s, period 15s
- **Graceful Shutdown**: `terminationGracePeriodSeconds: 60`
- **Security**: Runs as non-root user (UID 1000)

### service.yaml
- **Type**: ClusterIP (internal only; traffic routed via Ingress/ALB)
- **Port**: 80 → 8080 (container port)

### ingress.yaml
- **Controller**: AWS Load Balancer Controller (ALB)
- **Scheme**: internet-facing
- **Health Check Path**: `/actuator/health`
- **SSL Redirect**: HTTP → HTTPS (port 443)

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Default | Source |
|----------|-------------|---------|--------|
| `SPRING_DATASOURCE_URL` | Oracle JDBC URL | `jdbc:oracle:thin:@localhost:1521:ORCL` | Kubernetes Secret |
| `SPRING_DATASOURCE_USERNAME` | Oracle username | `admin` | Kubernetes Secret |
| `SPRING_DATASOURCE_PASSWORD` | Oracle password | — | Kubernetes Secret |
| `REDIS_HOST` | ElastiCache primary endpoint | `localhost` | Kubernetes ConfigMap |
| `REDIS_PORT` | Redis port | `6379` | Kubernetes ConfigMap |
| `REDIS_PASSWORD` | Redis AUTH token | — | Kubernetes Secret |
| `APP_PAYMENT_ENDPOINT` | Payment service URL | — | Kubernetes ConfigMap |
| `APP_INVENTORY_ENDPOINT` | Inventory service URL | — | Kubernetes ConfigMap |
| `APP_NOTIFICATION_ENDPOINT` | Notification service URL | — | Kubernetes ConfigMap |
| `REPORT_BASE_PATH` | Report file base path | `/var/reports` | Kubernetes ConfigMap |
| `BACKUP_PATH` | Backup file path | `/var/backups/nightly` | Kubernetes ConfigMap |
| `BOOKING_CACHE_TTL_SECONDS` | Redis cache TTL | `3600` | Kubernetes ConfigMap |
| `JAVA_OPTS` | JVM options | See Dockerfile | Kubernetes ConfigMap |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` | Deployment manifest |

### Using Kubernetes Secrets for Sensitive Values

```bash
# Create a secret for database credentials
kubectl create secret generic resortslite-db-secret \
  --from-literal=SPRING_DATASOURCE_URL='jdbc:oracle:thin:@<host>:1521:ORCL' \
  --from-literal=SPRING_DATASOURCE_USERNAME='admin' \
  --from-literal=SPRING_DATASOURCE_PASSWORD='<password>' \
  -n resortslite

# Create a secret for Redis credentials
kubectl create secret generic resortslite-redis-secret \
  --from-literal=REDIS_PASSWORD='<redis-auth-token>' \
  -n resortslite
```

### Using Kubernetes ConfigMaps for Non-Sensitive Values

```bash
kubectl create configmap resortslite-config \
  --from-literal=REDIS_HOST='<elasticache-endpoint>' \
  --from-literal=REDIS_PORT='6379' \
  --from-literal=REPORT_BASE_PATH='/var/reports' \
  --from-literal=BACKUP_PATH='/var/backups/nightly' \
  --from-literal=BOOKING_CACHE_TTL_SECONDS='3600' \
  -n resortslite
```

---

## Scaling and Management

### Horizontal Scaling

```bash
# Scale manually
kubectl scale deployment resortslite --replicas=4 -n resortslite

# Configure Horizontal Pod Autoscaler
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
  resortslite=<NEW_IMAGE_URI> \
  -n resortslite

# Monitor rollout
kubectl rollout status deployment/resortslite -n resortslite

# View rollout history
kubectl rollout history deployment/resortslite -n resortslite
```

### Rollback

```bash
# Rollback to previous version
kubectl rollout undo deployment/resortslite -n resortslite

# Rollback to specific revision
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

### Common Issues

| Symptom | Likely Cause | Resolution |
|---------|-------------|------------|
| `CrashLoopBackOff` | JVM OOM or missing env var | Check logs; increase memory limits; verify all env vars are set |
| `ImagePullBackOff` | ECR auth expired or wrong image URI | Re-authenticate with ECR; verify image URI |
| Readiness probe failing | App still starting (JVM warm-up) | Increase `initialDelaySeconds` in readinessProbe |
| Redis connection refused | Wrong `REDIS_HOST` or security group | Verify ElastiCache endpoint and EKS security group rules |
| Oracle connection refused | Wrong JDBC URL or security group | Verify Oracle endpoint and RDS/Oracle security group rules |
| ALB not provisioned | AWS Load Balancer Controller not installed | Install the controller (see Step 2) |
| Session not shared across pods | Redis not reachable | Verify `REDIS_HOST`, `REDIS_PORT`, and `REDIS_PASSWORD` |

### Useful Diagnostic Commands

```bash
# Get all resources in namespace
kubectl get all -n resortslite

# Exec into a running pod
kubectl exec -it deployment/resortslite -n resortslite -- /bin/sh

# Check application health directly
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite
curl http://localhost:8080/actuator/health

# View recent events
kubectl get events -n resortslite --sort-by='.lastTimestamp'
```

---

## Security Considerations

1. **Non-root container**: The application runs as UID 1000 (`appuser`) — never as root.
2. **Secrets management**: Store all credentials (DB password, Redis password) in Kubernetes Secrets, not ConfigMaps or environment variable literals in manifests.
3. **AWS Secrets Manager**: For production, consider using the AWS Secrets Manager CSI driver to inject secrets as files or environment variables.
4. **Network policies**: Apply Kubernetes NetworkPolicies to restrict pod-to-pod communication to only what is required.
5. **Image scanning**: Enable ECR image scanning on push to detect vulnerabilities in the container image.
6. **HTTPS only**: The Ingress manifest enforces HTTP → HTTPS redirect. Ensure a valid ACM certificate is attached to the ALB.
7. **Log4j**: The `pom.xml` includes `log4j-core:2.14.1` which has CVE-2021-44228 (Log4Shell). **Upgrade to `2.17.2` or later immediately.**
8. **commons-collections**: Version `3.2.1` has CVE-2015-6420 (RCE). **Upgrade to `3.2.2` or later.**
9. **Resource limits**: CPU and memory limits are set to prevent a single pod from consuming all node resources.
10. **Read-only filesystem**: Consider adding `readOnlyRootFilesystem: true` to the security context and mounting writable volumes only where needed.

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with:
```
-Xms256m -Xmx512m
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0
-XX:+UnlockExperimentalVMOptions
```

`UseContainerSupport` (available since Java 8u191) ensures the JVM respects container memory limits rather than reading host memory. `MaxRAMPercentage=75.0` caps heap at 75% of the container memory limit (1Gi → ~768Mi heap).

### Spring Boot Actuator

The health endpoint is exposed at `/actuator/health` with `show-details=always`. This is used by both Kubernetes liveness/readiness probes and the ALB health check.

### Spring Session + Redis

All HTTP sessions are stored in Amazon ElastiCache (Redis) via Spring Session (`@EnableRedisHttpSession`). This means:
- Sessions survive pod restarts
- Any pod can serve any session (no sticky sessions required on the ALB)
- Session timeout is 30 minutes (configurable via `spring.session.timeout`)

### Graceful Shutdown

`terminationGracePeriodSeconds: 60` gives the JVM 60 seconds to finish in-flight requests before the pod is forcibly terminated. Spring Boot 2.3+ supports graceful shutdown natively — enable it with `server.shutdown=graceful` in `application.properties` if needed.
