# ResortsLite — Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Building and Pushing the Docker Image](#building-and-pushing-the-docker-image)
6. [Azure AKS Deployment](#azure-aks-deployment)
7. [Kubernetes Manifest Reference](#kubernetes-manifest-reference)
8. [Configuration & Environment Variables](#configuration--environment-variables)
9. [Health Checks & Monitoring](#health-checks--monitoring)
10. [Scaling & Management](#scaling--management)
11. [Troubleshooting](#troubleshooting)
12. [Security Considerations](#security-considerations)

---

## Overview

**ResortsLite** is a Spring Boot 2.7.x / Java 8 resort booking microservice.  
It exposes a REST API for booking management, uses Redis for distributed caching, and
publishes events to Azure Service Bus for async processing.

| Property | Value |
|---|---|
| Framework | Spring Boot 2.7.18 |
| Java Version | 8 (eclipse-temurin:8-jdk) |
| Build Tool | Maven 3.8.x |
| Application Port | 8080 |
| Health Endpoint | `/actuator/health` |
| Target Platform | Azure AKS |

---

## Prerequisites

### Local Development
- Docker Desktop 24+ (with Compose v2)
- Java 8 JDK (for local builds outside Docker)
- Maven 3.8+ (for local builds outside Docker)

### Azure AKS Deployment
- [Azure CLI](https://learn.microsoft.com/en-us/cli/azure/install-azure-cli) (`az`) — v2.50+
- [kubectl](https://kubernetes.io/docs/tasks/tools/) — v1.27+
- An active Azure subscription
- An Azure Container Registry (ACR) **or** Docker Hub account
- An AKS cluster (see [AKS Cluster Setup](#aks-cluster-setup))

---

## Project Structure

```
Vast/
├── Dockerfile                  # Multi-stage build (Maven builder + eclipse-temurin:8-jdk runtime)
├── docker-compose.yml          # Local development (application only)
├── .dockerignore               # Excludes build artefacts and wrapper files
├── pom.xml                     # Maven project descriptor
├── src/                        # Java source code
├── kubernetes/
│   ├── namespace.yaml          # Kubernetes namespace: resortslite
│   ├── deployment.yaml         # Deployment (2 replicas, resource limits, health probes)
│   ├── service.yaml            # ClusterIP service on port 80 → 8080
│   └── ingress.yaml            # Azure Application Gateway Ingress
├── scripts/
│   ├── build-push.sh           # Linux/macOS: build & push Docker image
│   ├── build-push.bat          # Windows: build & push Docker image
│   ├── deploy-image.sh         # Linux/macOS: deploy to AKS
│   └── deploy-image.bat        # Windows: deploy to AKS
└── docs/
    └── DEPLOYMENT.md           # This file
```

---

## Local Development with Docker Compose

### 1. Configure environment variables

Create a `.env` file in the project root (never commit this file):

```dotenv
# Redis (provide an external Redis instance or run one separately)
REDIS_HOST=localhost
REDIS_PORT=6379
BOOKING_CACHE_TTL_SECONDS=3600

# Azure Service Bus (leave empty to run in local dev mode without messaging)
AZURE_SERVICE_BUS_CONNECTION_STRING=
AZURE_SERVICE_BUS_BOOKING_QUEUE=booking-events-queue
AZURE_SERVICE_BUS_REPORT_QUEUE=report-events-queue

# JWT secret (change for any non-local environment)
JWT_SECRET_KEY=my-local-dev-secret-key-at-least-32-chars

# Report / backup paths
REPORT_BASE_PATH=/mnt/reports
BACKUP_PATH=/mnt/backups

# Database host (H2 in-memory is used by default)
DB_HOST=localhost
```

### 2. Start the application

```bash
docker compose up --build
```

### 3. Verify the application is running

```bash
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP",...}
```

### 4. Access the H2 console (development only)

Open `http://localhost:8080/h2-console` in your browser.  
JDBC URL: `jdbc:h2:mem:resortdb`  
Username: `sa` | Password: *(empty)*

### 5. Stop the application

```bash
docker compose down
```

---

## Building and Pushing the Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

The script will prompt you to:
1. Choose a registry (Azure ACR or Docker Hub)
2. Enter registry credentials / ACR name
3. Enter an image tag (defaults to `latest`)

### Windows

```cmd
scripts\build-push.bat
```

### Manual build (for reference)

```bash
# Build
docker build -t resortslite:latest .

# Tag for ACR
docker tag resortslite:latest <ACR_NAME>.azurecr.io/resortslite:latest

# Push to ACR
az acr login --name <ACR_NAME>
docker push <ACR_NAME>.azurecr.io/resortslite:latest
```

---

## Azure AKS Deployment

### AKS Cluster Setup

If you do not have an AKS cluster, create one:

```bash
# Login to Azure
az login

# Create resource group
az group create --name resortslite-rg --location eastus

# Create ACR
az acr create --resource-group resortslite-rg \
  --name <ACR_NAME> --sku Basic

# Create AKS cluster with ACR integration
az aks create \
  --resource-group resortslite-rg \
  --name resortslite-aks \
  --node-count 2 \
  --node-vm-size Standard_DS2_v2 \
  --attach-acr <ACR_NAME> \
  --enable-addons ingress-appgw \
  --appgw-name resortslite-appgw \
  --appgw-subnet-cidr "10.225.0.0/16" \
  --generate-ssh-keys

# Get credentials
az aks get-credentials --resource-group resortslite-rg --name resortslite-aks
```

### Redis Setup on AKS (required for distributed caching)

```bash
# Add Bitnami Helm repo
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

# Install Redis in the resortslite namespace
helm install redis bitnami/redis \
  --set architecture=standalone \
  --set auth.enabled=false \
  --set master.persistence.enabled=true \
  --set master.persistence.storageClass=managed-premium \
  --set master.persistence.size=8Gi \
  --namespace resortslite \
  --create-namespace

# Verify Redis is running
kubectl get pods -n resortslite -l app.kubernetes.io/name=redis
```

Redis DNS name inside the cluster: `redis-master.resortslite.svc.cluster.local`

### Deploy ResortsLite

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
- Azure Resource Group and AKS Cluster name
- Full Docker image URI (e.g. `myregistry.azurecr.io/resortslite:1.0.0`)
- All required environment variables (Redis, Service Bus, JWT, DB host)

### Manual deployment (for reference)

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

## Kubernetes Manifest Reference

### namespace.yaml
Creates the `resortslite` namespace to isolate all application resources.

### deployment.yaml
| Setting | Value |
|---|---|
| Replicas | 2 |
| Image | `{{IMAGE_URI}}` (replaced at deploy time) |
| Container Port | 8080 |
| CPU Request | 250m |
| CPU Limit | 500m |
| Memory Request | 512Mi |
| Memory Limit | 1Gi |
| Liveness Probe | `GET /actuator/health` — initial delay 60s, period 30s |
| Readiness Probe | `GET /actuator/health` — initial delay 30s, period 15s |

### service.yaml
ClusterIP service mapping port 80 → container port 8080.

### ingress.yaml
Azure Application Gateway Ingress Controller (AGIC) rule routing all traffic
from `resortslite.example.com` to the ClusterIP service.  
Update the `host` field to your actual domain before deploying.

---

## Configuration & Environment Variables

| Variable | Default | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP server port |
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `REDIS_HOST` | `localhost` | Redis server hostname |
| `REDIS_PORT` | `6379` | Redis server port |
| `BOOKING_CACHE_TTL_SECONDS` | `3600` | Booking cache TTL (seconds) |
| `AZURE_SERVICE_BUS_CONNECTION_STRING` | *(empty)* | Azure Service Bus connection string |
| `AZURE_SERVICE_BUS_BOOKING_QUEUE` | `booking-events-queue` | Booking events queue name |
| `AZURE_SERVICE_BUS_REPORT_QUEUE` | `report-events-queue` | Report events queue name |
| `JWT_SECRET_KEY` | `default-dev-secret-key-replace-in-production` | JWT signing key (min 32 chars) |
| `REPORT_BASE_PATH` | `/mnt/reports` | Report file storage path |
| `BACKUP_PATH` | `/mnt/backups` | Backup file storage path |
| `DB_HOST` | `localhost` | Database hostname |
| `JAVA_OPTS` | `-Xms256m -Xmx512m ...` | JVM startup options |

### Storing secrets in Azure Key Vault

For production deployments, store sensitive values (JWT_SECRET_KEY,
AZURE_SERVICE_BUS_CONNECTION_STRING) in Azure Key Vault and mount them
using the [Azure Key Vault CSI Driver](https://learn.microsoft.com/en-us/azure/aks/csi-secrets-store-driver):

```bash
# Enable the CSI driver add-on
az aks enable-addons --addons azure-keyvault-secrets-provider \
  --name resortslite-aks --resource-group resortslite-rg
```

---

## Health Checks & Monitoring

### Health endpoint

```bash
curl http://<INGRESS_IP>/actuator/health
```

Expected response:
```json
{"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"},"diskSpace":{"status":"UP"}}}
```

### Pod logs

```bash
# All pods
kubectl logs -l app=resortslite -n resortslite --tail=100 -f

# Specific pod
kubectl logs <POD_NAME> -n resortslite -f
```

### Pod status

```bash
kubectl get pods -n resortslite -o wide
kubectl describe pod <POD_NAME> -n resortslite
```

---

## Scaling & Management

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

kubectl get hpa -n resortslite
```

### Rolling update (new image)

```bash
kubectl set image deployment/resortslite \
  resortslite=<NEW_IMAGE_URI> \
  -n resortslite

kubectl rollout status deployment/resortslite -n resortslite
```

### Rollback

```bash
kubectl rollout undo deployment/resortslite -n resortslite
kubectl rollout history deployment/resortslite -n resortslite
```

---

## Troubleshooting

### Pod stuck in `Pending`
```bash
kubectl describe pod <POD_NAME> -n resortslite
# Check Events section for resource/scheduling issues
```

### Pod in `CrashLoopBackOff`
```bash
kubectl logs <POD_NAME> -n resortslite --previous
# Look for Java startup errors, missing env vars, or Redis connection failures
```

### Redis connection failure
- Verify Redis is running: `kubectl get pods -n resortslite -l app.kubernetes.io/name=redis`
- Check `REDIS_HOST` env var matches the Redis service DNS name
- Default in-cluster DNS: `redis-master.resortslite.svc.cluster.local`

### Azure Service Bus not publishing
- Verify `AZURE_SERVICE_BUS_CONNECTION_STRING` is set correctly
- The application logs a WARNING (not an error) when the connection string is empty — this is expected in local dev mode

### Ingress not routing traffic
```bash
kubectl describe ingress resortslite-ingress -n resortslite
# Verify AGIC add-on is enabled and Application Gateway is provisioned
az aks show --resource-group resortslite-rg --name resortslite-aks \
  --query "addonProfiles.ingressApplicationGateway"
```

### Image pull errors
```bash
kubectl describe pod <POD_NAME> -n resortslite
# If "ImagePullBackOff": verify ACR is attached to AKS
az aks update --resource-group resortslite-rg \
  --name resortslite-aks --attach-acr <ACR_NAME>
```

---

## Security Considerations

1. **JWT_SECRET_KEY**: Must be at least 32 characters. Store in Azure Key Vault — never hardcode.
2. **Azure Service Bus Connection String**: Contains shared access keys. Store in Azure Key Vault.
3. **H2 Console**: Disable in production by setting `spring.h2.console.enabled=false`.
4. **Non-root container**: The runtime image runs as `appuser` (UID 1000) — do not override `runAsUser`.
5. **Network policies**: Consider adding Kubernetes NetworkPolicy resources to restrict pod-to-pod traffic.
6. **TLS**: Enable TLS on the Application Gateway Ingress for production traffic.
7. **Image scanning**: Scan the built image with `az acr check-health` or Trivy before deploying.
8. **Dependency vulnerabilities**: The pom.xml includes log4j 2.14.1 (CVE-2021-44228) and commons-collections 3.2.1 (CVE-2015-6420). Upgrade these before deploying to production.
