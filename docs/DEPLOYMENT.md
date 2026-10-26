# ResortsLite — Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Building and Pushing the Docker Image](#building-and-pushing-the-docker-image)
6. [AWS EKS Deployment](#aws-eks-deployment)
7. [Configuration Management](#configuration-management)
8. [Health Checks and Monitoring](#health-checks-and-monitoring)
9. [Scaling and Rolling Updates](#scaling-and-rolling-updates)
10. [Troubleshooting](#troubleshooting)
11. [Security Considerations](#security-considerations)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.x  
**Java Version**: Java 8  
**Build Tool**: Maven  
**Target Platform**: AWS EKS (Elastic Kubernetes Service)  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

ResortsLite is a legacy resort booking application modernised for cloud-native deployment on AWS EKS. It uses:
- **Amazon ElastiCache for Redis** — distributed session storage and booking cache
- **Oracle Database** — persistent booking data
- **Spring Boot Actuator** — health and readiness probes
- **AWS ALB Ingress Controller** — internet-facing load balancing

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker Desktop | 24.x+ | Build and run containers |
| Docker Compose | 2.x+ | Local multi-container orchestration |
| Java JDK 8 | 8u392+ | Local build (optional) |
| Maven | 3.8.x+ | Local build (optional) |

### AWS EKS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS authentication and ECR operations |
| kubectl | 1.28+ | Kubernetes cluster management |
| eksctl | 0.170+ | EKS cluster creation (optional) |

### AWS IAM Permissions Required
```
ecr:GetAuthorizationToken
ecr:BatchCheckLayerAvailability
ecr:GetDownloadUrlForLayer
ecr:BatchGetImage
ecr:CreateRepository
ecr:DescribeRepositories
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
Test_Comp1/
├── Dockerfile                    # Multi-stage Docker build (Java 8 / Maven)
├── docker-compose.yml            # Local development compose file
├── .dockerignore                 # Files excluded from Docker build context
├── pom.xml                       # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   ├── ReportService.java
│       │   ├── RedisConfig.java
│       │   └── SessionConfig.java
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml            # Kubernetes namespace
│   ├── deployment.yaml           # Application deployment (2 replicas)
│   ├── service.yaml              # ClusterIP service
│   └── ingress.yaml              # AWS ALB ingress
├── scripts/
│   ├── build-push.sh             # Linux/macOS: build and push image
│   ├── build-push.bat            # Windows: build and push image
│   ├── deploy-image.sh           # Linux/macOS: deploy to EKS
│   └── deploy-image.bat          # Windows: deploy to EKS
└── docs/
    └── DEPLOYMENT.md             # This file
```

---

## Local Development with Docker Compose

### Step 1 — Configure environment variables

Create a `.env` file in the project root (never commit this file):

```bash
# Oracle Database
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=your_password_here

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_SSL=false

# Session
SESSION_TIMEOUT_SECONDS=1800

# Booking cache
BOOKING_CACHE_TTL_SECONDS=3600

# Payment API
PAYMENT_API_URL=http://payment-service:9090/payments/charge

# File paths
REPORT_BASE_PATH=/var/reports
BACKUP_PATH=/var/backups
```

### Step 2 — Start the application

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

### Step 3 — Verify the application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05"
```

---

## Building and Pushing the Docker Image

### Linux / macOS

```bash
# Make the script executable
chmod +x scripts/build-push.sh

# Run from the repository root
./scripts/build-push.sh
```

The script will prompt you to:
1. Select registry type (AWS ECR or Docker Hub)
2. Enter registry credentials / AWS account details
3. Enter an image tag (defaults to `latest`)

### Windows

```cmd
REM Run from the repository root
scripts\build-push.bat
```

### Manual Build (without script)

```bash
# Build
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

### Step 1 — Configure AWS CLI

```bash
aws configure
# Enter: AWS Access Key ID, Secret Access Key, Region, Output format
```

### Step 2 — Create or connect to an EKS cluster

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

# Or update kubeconfig for an existing cluster
aws eks update-kubeconfig --region us-east-1 --name resortslite-cluster
```

### Step 3 — Install AWS Load Balancer Controller

The ingress manifest uses the AWS ALB Ingress Controller. Install it on your cluster:

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

### Step 4 — Create Kubernetes Secrets

Before deploying, create secrets for database and Redis credentials:

```bash
# Database credentials
kubectl create secret generic resortslite-db-secret \
  --namespace resortslite \
  --from-literal=username=<DB_USERNAME> \
  --from-literal=password=<DB_PASSWORD>

# Redis credentials (if password-protected)
kubectl create secret generic resortslite-redis-secret \
  --namespace resortslite \
  --from-literal=password=<REDIS_PASSWORD>
```

### Step 5 — Deploy using the script

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
- AWS region
- EKS cluster name
- Full Docker image URI (e.g., `123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest`)
- Application environment variables (datasource URL, Redis host, etc.)

### Step 6 — Manual deployment (without script)

```bash
# Apply manifests in order
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

# Wait for rollout
kubectl rollout status deployment/resortslite -n resortslite

# Verify
kubectl get pods,svc,ingress -n resortslite
```

---

## Configuration Management

### Environment Variables Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `SPRING_DATASOURCE_URL` | — | Oracle JDBC connection URL |
| `SPRING_DATASOURCE_USERNAME` | — | Database username |
| `SPRING_DATASOURCE_PASSWORD` | — | Database password |
| `REDIS_HOST` | `localhost` | Redis / ElastiCache hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | _(empty)_ | Redis password |
| `REDIS_SSL` | `false` | Enable TLS for Redis |
| `SESSION_TIMEOUT_SECONDS` | `1800` | HTTP session TTL (30 min) |
| `BOOKING_CACHE_TTL_SECONDS` | `3600` | Booking cache TTL (1 hour) |
| `PAYMENT_API_URL` | — | Payment service endpoint |
| `REPORT_BASE_PATH` | `/var/reports` | Report file directory |
| `BACKUP_PATH` | `/var/backups` | Backup file directory |
| `JAVA_OPTS` | See Dockerfile | JVM tuning flags |

### Using AWS Secrets Manager

For production, store sensitive values in AWS Secrets Manager and inject them using the AWS Secrets and Configuration Provider (ASCP):

```bash
# Store database credentials
aws secretsmanager create-secret \
  --name resortslite/db-credentials \
  --secret-string '{"username":"admin","password":"<password>"}'

# Store Redis password
aws secretsmanager create-secret \
  --name resortslite/redis-credentials \
  --secret-string '{"password":"<redis-password>"}'
```

---

## Health Checks and Monitoring

### Spring Boot Actuator Endpoints

| Endpoint | URL | Purpose |
|----------|-----|---------|
| Health | `GET /actuator/health` | Liveness and readiness probe |

### Kubernetes Probes

The deployment is configured with:

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 90   # Allow JVM startup time
  periodSeconds: 30
  timeoutSeconds: 10
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 60
  periodSeconds: 15
  timeoutSeconds: 5
  failureThreshold: 3
```

> **Note**: The `initialDelaySeconds` values are set conservatively for Java 8 JVM startup. Adjust based on observed startup times in your environment.

### Check pod health

```bash
# View pod status
kubectl get pods -n resortslite

# View pod logs
kubectl logs -l app=resortslite -n resortslite --tail=100

# Describe a pod for events
kubectl describe pod <pod-name> -n resortslite
```

---

## Scaling and Rolling Updates

### Manual scaling

```bash
# Scale to 3 replicas
kubectl scale deployment resortslite --replicas=3 -n resortslite
```

### Horizontal Pod Autoscaler (HPA)

```bash
kubectl autoscale deployment resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortslite
```

### Rolling update (new image)

```bash
# Update the image
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

# Rollback to a specific revision
kubectl rollout history deployment/resortslite -n resortslite
kubectl rollout undo deployment/resortslite --to-revision=2 -n resortslite
```

---

## Troubleshooting

### Pod fails to start

```bash
# Check pod events
kubectl describe pod <pod-name> -n resortslite

# Check logs
kubectl logs <pod-name> -n resortslite

# Check previous container logs (if restarting)
kubectl logs <pod-name> -n resortslite --previous
```

### Common issues

| Symptom | Likely Cause | Resolution |
|---------|-------------|------------|
| `CrashLoopBackOff` | Application startup failure | Check logs; verify DB/Redis connectivity |
| `ImagePullBackOff` | ECR authentication failure | Verify node IAM role has ECR pull permissions |
| `Pending` pods | Insufficient cluster resources | Scale node group or reduce resource requests |
| Health probe failing | JVM not started yet | Increase `initialDelaySeconds` |
| Redis connection refused | Wrong `REDIS_HOST` | Verify ElastiCache endpoint and security groups |
| Oracle connection refused | Wrong datasource URL | Verify Oracle endpoint and VPC routing |

### Ingress not getting an address

```bash
# Check ALB controller logs
kubectl logs -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller

# Verify ingress annotations
kubectl describe ingress resortslite-ingress -n resortslite
```

### Check service connectivity

```bash
# Port-forward for local testing
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite

# Test health endpoint
curl http://localhost:8080/actuator/health
```

---

## Security Considerations

1. **Secrets Management**: Never store database passwords or Redis credentials in `application.properties` or Kubernetes ConfigMaps. Use Kubernetes Secrets backed by AWS Secrets Manager.

2. **IRSA (IAM Roles for Service Accounts)**: Configure IRSA for the `resortslite-sa` service account to grant credential-free access to ElastiCache and other AWS services.

3. **Non-root container**: The Dockerfile runs the application as a non-root user (`appuser`). Do not override `runAsUser` in the pod spec.

4. **Network policies**: Consider adding Kubernetes NetworkPolicies to restrict pod-to-pod communication to only required paths.

5. **Image scanning**: Enable ECR image scanning on push to detect vulnerabilities in the container image.

6. **TLS**: The ingress is configured to redirect HTTP to HTTPS. Ensure an ACM certificate is attached to the ALB.

7. **Log4j**: The current `pom.xml` includes `log4j-core:2.14.1` which has the critical CVE-2021-44228 (Log4Shell) vulnerability. **Upgrade to `2.17.1` or later immediately.**

8. **commons-collections**: Version `3.2.1` has CVE-2015-6420 (RCE). **Upgrade to `3.2.2` or later.**

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with:
```
-Xms256m -Xmx512m
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0
```

`UseContainerSupport` ensures the JVM respects container memory limits rather than using host memory. `MaxRAMPercentage=75.0` caps heap at 75% of the container memory limit (1Gi → ~768Mi effective max).

Adjust `JAVA_OPTS` in the deployment environment variables if the application requires more memory.

### Spring Boot Startup Time

Java 8 Spring Boot applications typically take 30–90 seconds to start depending on classpath size and database connectivity. The Kubernetes probes use `initialDelaySeconds: 90` (liveness) and `60` (readiness) to accommodate this. Monitor actual startup times and tune accordingly.

### Graceful Shutdown

The deployment sets `terminationGracePeriodSeconds: 60`. Spring Boot 2.7.x supports graceful shutdown via `server.shutdown=graceful` in `application.properties`. Add this property to allow in-flight requests to complete before the pod terminates.
