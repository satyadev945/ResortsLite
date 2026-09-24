# ResortsLite – Deployment Guide (GCP GKE)

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Local Development with Docker Compose](#local-development-with-docker-compose)
4. [Build & Push Docker Image](#build--push-docker-image)
5. [GCP GKE Prerequisites](#gcp-gke-prerequisites)
6. [GKE Cluster Setup](#gke-cluster-setup)
7. [Kubernetes Deployment Walkthrough](#kubernetes-deployment-walkthrough)
8. [Configuration Management](#configuration-management)
9. [GKE Scaling & Management](#gke-scaling--management)
10. [Troubleshooting](#troubleshooting)
11. [Security Considerations](#security-considerations)
12. [Java-Specific Notes](#java-specific-notes)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.x  
**Java Version**: 8  
**Build Tool**: Maven  
**Runtime Image**: `eclipse-temurin:8-jre`  
**Application Port**: `8080`  
**Health Endpoint**: `/actuator/health`  
**Target Platform**: Google Kubernetes Engine (GKE)

ResortsLite is a legacy resort booking REST API that has been modernised for cloud-native deployment on GKE. It uses:
- **Spring Session + Redis** (Google Cloud Memorystore) for distributed session management
- **Spring Boot Actuator** for health and readiness probes
- **H2 in-memory database** (development) / configurable JDBC datasource (production)

---

## Prerequisites

### Local Machine
| Tool | Minimum Version | Install |
|------|----------------|---------|
| Docker | 24.x | https://docs.docker.com/get-docker/ |
| Docker Compose | 2.x | Bundled with Docker Desktop |
| Java JDK | 8 | https://adoptium.net/ |
| Maven | 3.8+ | https://maven.apache.org/download.cgi |
| gcloud CLI | latest | https://cloud.google.com/sdk/docs/install |
| kubectl | 1.27+ | `gcloud components install kubectl` |

### GCP Resources
- GCP project with billing enabled
- GKE cluster (Standard or Autopilot)
- Google Artifact Registry repository (optional, or use Docker Hub)
- Google Cloud Memorystore for Redis instance (for session management)

---

## Local Development with Docker Compose

### 1. Clone and configure environment

```bash
git clone <repository-url>
cd MmonoApDb
```

Create a `.env` file in the project root:

```env
# Redis (use localhost if running Redis locally)
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=

# External service endpoints
PAYMENT_API_URL=http://payment-svc.payments.svc.cluster.local:9090/payments/charge
REPORT_BASE_PATH=/var/legacy/reports/
```

### 2. Start the application

```bash
docker compose up --build
```

### 3. Verify the application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=STANDARD&checkIn=2024-06-01&checkOut=2024-06-05"
```

### 4. Stop the application

```bash
docker compose down
```

---

## Build & Push Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
bash scripts/build-push.sh
```

The script will prompt you to:
1. Select registry type (Google Artifact Registry or Docker Hub)
2. Enter registry credentials / project details
3. Enter an image tag (defaults to `latest`)

### Windows

```cmd
scripts\build-push.bat
```

### Manual build (example)

```bash
# Google Artifact Registry
docker build -f Dockerfile \
  -t us-central1-docker.pkg.dev/MY_PROJECT/MY_REPO/resortslite:1.0.0 .

docker push us-central1-docker.pkg.dev/MY_PROJECT/MY_REPO/resortslite:1.0.0
```

---

## GCP GKE Prerequisites

### 1. Install and initialise gcloud

```bash
gcloud init
gcloud auth login
gcloud config set project YOUR_GCP_PROJECT_ID
```

### 2. Enable required APIs

```bash
gcloud services enable container.googleapis.com
gcloud services enable artifactregistry.googleapis.com
gcloud services enable redis.googleapis.com
```

### 3. Create Artifact Registry repository (if using GAR)

```bash
gcloud artifacts repositories create resortslite-repo \
  --repository-format=docker \
  --location=us-central1 \
  --description="ResortsLite container images"
```

### 4. Create Google Cloud Memorystore for Redis

```bash
gcloud redis instances create resortslite-redis \
  --size=1 \
  --region=us-central1 \
  --redis-version=redis_6_x \
  --tier=BASIC
```

Note the Redis instance IP address for use in deployment.

---

## GKE Cluster Setup

### 1. Create a GKE cluster (if not existing)

```bash
# Standard cluster
gcloud container clusters create resortslite-cluster \
  --zone us-central1-a \
  --num-nodes 3 \
  --machine-type e2-standard-2

# OR Autopilot cluster
gcloud container clusters create-auto resortslite-cluster \
  --region us-central1
```

### 2. Configure kubectl

```bash
gcloud container clusters get-credentials resortslite-cluster \
  --zone us-central1-a \
  --project YOUR_GCP_PROJECT_ID
```

### 3. Verify connectivity

```bash
kubectl cluster-info
kubectl get nodes
```

---

## Kubernetes Deployment Walkthrough

### Manifest Files

| File | Description |
|------|-------------|
| `kubernetes/namespace.yaml` | Creates the `resortslite` namespace |
| `kubernetes/deployment.yaml` | Deployment with 2 replicas, health probes, resource limits |
| `kubernetes/service.yaml` | ClusterIP service exposing port 80 → 8080 |
| `kubernetes/ingress.yaml` | GCE Ingress for external HTTP access |

### Deploy using the script

#### Linux / macOS

```bash
chmod +x scripts/deploy-image.sh
bash scripts/deploy-image.sh
```

#### Windows

```cmd
scripts\deploy-image.bat
```

The script will prompt for:
- GCP Project ID, Zone, Cluster name
- Full Docker image URI
- Redis connection details (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD)
- Payment API URL
- Report base path

### Manual deployment

```bash
# 1. Apply namespace
kubectl apply -f kubernetes/namespace.yaml

# 2. Patch and apply deployment (replace placeholders)
sed -i 's|{{IMAGE_URI}}|us-central1-docker.pkg.dev/MY_PROJECT/MY_REPO/resortslite:1.0.0|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_HOST}}|10.0.0.5|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_PORT}}|6379|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_PASSWORD}}||g' kubernetes/deployment.yaml
sed -i 's|{{PAYMENT_API_URL}}|http://payment-svc.payments.svc.cluster.local:9090/payments/charge|g' kubernetes/deployment.yaml
sed -i 's|{{REPORT_BASE_PATH}}|/var/legacy/reports/|g' kubernetes/deployment.yaml
kubectl apply -f kubernetes/deployment.yaml

# 3. Apply service
kubectl apply -f kubernetes/service.yaml

# 4. Apply ingress
kubectl apply -f kubernetes/ingress.yaml

# 5. Wait for rollout
kubectl rollout status deployment/resortslite -n resortslite

# 6. Verify
kubectl get pods,svc,ingress -n resortslite
```

### Check application health

```bash
# Port-forward for local testing
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite

# In another terminal
curl http://localhost:8080/actuator/health
```

---

## Configuration Management

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` |
| `REDIS_HOST` | Memorystore Redis IP/hostname | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis auth password | _(empty)_ |
| `PAYMENT_API_URL` | Payment service endpoint | _(cluster DNS)_ |
| `REPORT_BASE_PATH` | Report file base path | `/var/legacy/reports/` |
| `JAVA_OPTS` | JVM options | See Dockerfile |

### Using GKE Secrets for sensitive values

```bash
# Create a secret for Redis password
kubectl create secret generic resortslite-secrets \
  --from-literal=REDIS_PASSWORD=your-redis-password \
  -n resortslite
```

Reference in deployment.yaml:
```yaml
- name: REDIS_PASSWORD
  valueFrom:
    secretKeyRef:
      name: resortslite-secrets
      key: REDIS_PASSWORD
```

### Using ConfigMaps for non-sensitive config

```bash
kubectl create configmap resortslite-config \
  --from-literal=REDIS_HOST=10.0.0.5 \
  --from-literal=REDIS_PORT=6379 \
  --from-literal=PAYMENT_API_URL=http://payment-svc.payments.svc.cluster.local:9090/payments/charge \
  -n resortslite
```

---

## GKE Scaling & Management

### Manual scaling

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

### Rolling update (new image)

```bash
kubectl set image deployment/resortslite \
  resortslite=us-central1-docker.pkg.dev/MY_PROJECT/MY_REPO/resortslite:2.0.0 \
  -n resortslite

kubectl rollout status deployment/resortslite -n resortslite
```

### Rollback

```bash
kubectl rollout undo deployment/resortslite -n resortslite

# Rollback to specific revision
kubectl rollout undo deployment/resortslite --to-revision=2 -n resortslite
```

### View rollout history

```bash
kubectl rollout history deployment/resortslite -n resortslite
```

---

## Troubleshooting

### Pod not starting

```bash
# Check pod status
kubectl get pods -n resortslite

# Describe pod for events
kubectl describe pod <pod-name> -n resortslite

# View logs
kubectl logs <pod-name> -n resortslite
kubectl logs <pod-name> -n resortslite --previous  # crashed pod
```

### Common issues

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| `CrashLoopBackOff` | Redis connection failure | Verify `REDIS_HOST` and `REDIS_PORT` env vars |
| `ImagePullBackOff` | Wrong image URI or missing credentials | Check image URI; configure imagePullSecrets |
| `Pending` pods | Insufficient cluster resources | Scale cluster or reduce resource requests |
| Health probe failing | JVM slow startup | Increase `initialDelaySeconds` in probes |
| `OOMKilled` | Insufficient memory limit | Increase memory limit in deployment.yaml |

### Redis connectivity

```bash
# Exec into pod and test Redis
kubectl exec -it <pod-name> -n resortslite -- sh
# Inside pod:
# nc -zv $REDIS_HOST $REDIS_PORT
```

### Ingress not getting IP

```bash
# Check ingress status
kubectl describe ingress resortslite-ingress -n resortslite

# Ensure GKE Ingress controller is enabled
gcloud container clusters describe resortslite-cluster \
  --zone us-central1-a \
  --format="value(addonsConfig.httpLoadBalancing.disabled)"
# Should return empty (not disabled)
```

### View actuator health details

```bash
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite
curl http://localhost:8080/actuator/health | python3 -m json.tool
```

---

## Security Considerations

1. **Non-root container**: The application runs as `appuser` (UID 1000) — never as root.
2. **Secrets management**: Use GKE Workload Identity + Secret Manager for Redis passwords and API keys. Never hardcode credentials.
3. **Network policies**: Consider adding Kubernetes NetworkPolicy to restrict pod-to-pod traffic.
4. **Image scanning**: Enable Artifact Registry vulnerability scanning on pushed images.
5. **RBAC**: Apply least-privilege RBAC roles for the service account running the pods.
6. **TLS**: Configure TLS on the Ingress using a managed certificate:
   ```bash
   gcloud compute ssl-certificates create resortslite-cert \
     --domains=resortslite.example.com
   ```
7. **Dependency vulnerabilities**: The current pom.xml includes `log4j-core:2.14.1` (CVE-2021-44228) and `commons-collections:3.2.1` (CVE-2015-6420). **These MUST be upgraded before production deployment.**

---

## Java-Specific Notes

### JVM Container Awareness
The Dockerfile sets the following JVM flags for optimal container behaviour:
```
-XX:+UseContainerSupport       # Respect container CPU/memory limits
-XX:MaxRAMPercentage=75.0      # Use 75% of container memory for heap
-XX:+UnlockExperimentalVMOptions
-Xms256m                       # Initial heap
-Xmx512m                       # Maximum heap
-Djava.security.egd=file:/dev/./urandom  # Faster SecureRandom
```

### Spring Boot Actuator
Health endpoint is exposed at `/actuator/health`. The Kubernetes liveness and readiness probes both use this endpoint.

To view full health details:
```bash
curl http://localhost:8080/actuator/health
```

### Spring Session + Redis
Sessions are stored in Google Cloud Memorystore for Redis. Session TTL is 1800 seconds (30 minutes). Ensure the GKE cluster has VPC peering or Private Service Access configured to reach the Memorystore instance.

### H2 Console (Development Only)
The H2 console is available at `/h2-console` when running locally. **Disable this in production** by setting:
```
spring.h2.console.enabled=false
```

### Graceful Shutdown
The container is configured with `terminationGracePeriodSeconds: 30`. Spring Boot 2.7.x supports graceful shutdown — add the following to `application.properties` for production:
```
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=20s
```
