# ResortsLite — Deployment Guide

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.18  
**Java Version**: Java 8  
**Build Tool**: Maven  
**Target Platform**: Azure Kubernetes Service (AKS)  
**Package Type**: Executable JAR  

ResortsLite is a legacy resort booking REST API built on Spring Boot 2.7.x / Java 8. It uses Oracle Database for persistence, Azure Cache for Redis for distributed session management and caching, and exposes Spring Boot Actuator endpoints for health monitoring.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Project Structure](#project-structure)
3. [Local Development with Docker Compose](#local-development-with-docker-compose)
4. [Build and Push Docker Image](#build-and-push-docker-image)
5. [Azure AKS Prerequisites](#azure-aks-prerequisites)
6. [AKS Cluster Setup](#aks-cluster-setup)
7. [Kubernetes Deployment](#kubernetes-deployment)
8. [Configuration Management](#configuration-management)
9. [Health Checks and Monitoring](#health-checks-and-monitoring)
10. [Scaling and Management](#scaling-and-management)
11. [Troubleshooting](#troubleshooting)
12. [Security Considerations](#security-considerations)

---

## Prerequisites

### Local Development
- Docker Desktop 24.x or later
- Docker Compose v2.x or later
- Java 8 JDK (for local builds outside Docker)
- Maven 3.8.x or later (for local builds outside Docker)

### Cloud Deployment
- Azure CLI (`az`) 2.50+
- `kubectl` 1.27+
- An active Azure subscription
- Azure Container Registry (ACR) or Docker Hub account
- Azure Kubernetes Service (AKS) cluster (1.27+)
- Azure Cache for Redis instance
- Oracle Database (or compatible JDBC endpoint)

---

## Project Structure

```
.
├── Dockerfile                  # Multi-stage Docker build (Java 8)
├── docker-compose.yml          # Local single-service compose file
├── .dockerignore               # Excludes build artifacts and wrapper files
├── pom.xml                     # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml          # Kubernetes namespace
│   ├── deployment.yaml         # Deployment with 2 replicas
│   ├── service.yaml            # ClusterIP service
│   └── ingress.yaml            # Azure Application Gateway ingress
├── scripts/
│   ├── build-push.sh           # Linux/macOS build & push script
│   ├── build-push.bat          # Windows build & push script
│   ├── deploy-image.sh         # Linux/macOS AKS deploy script
│   └── deploy-image.bat        # Windows AKS deploy script
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
SPRING_DATASOURCE_PASSWORD=yourpassword

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_SSL=false

# Internal service endpoints
APP_PAYMENT_ENDPOINT=http://payment-svc:9090/charge
APP_INVENTORY_ENDPOINT=http://inventory-svc:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://notify-svc:7070/send

# File paths
REPORT_BASE_PATH=/var/reports
BACKUP_PATH=/var/backups/nightly
```

### 2. Build and Start

```bash
# Build and start the application container
docker compose up --build

# Run in background
docker compose up --build -d

# View logs
docker compose logs -f resortslite

# Stop
docker compose down
```

### 3. Verify Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=Suite&checkIn=2024-01-01&checkOut=2024-01-05"
```

---

## Build and Push Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

The script will prompt you to:
1. Select registry type (Azure ACR or Docker Hub)
2. Enter registry credentials/name
3. Enter an image tag (defaults to `latest`)

### Windows

```cmd
scripts\build-push.bat
```

### Manual Build

```bash
# Build image
docker build -t resortslite:latest .

# Tag for ACR
docker tag resortslite:latest <ACR_NAME>.azurecr.io/resortslite:latest

# Push to ACR
az acr login --name <ACR_NAME>
docker push <ACR_NAME>.azurecr.io/resortslite:latest
```

---

## Azure AKS Prerequisites

### 1. Install Required Tools

```bash
# Install Azure CLI
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash

# Install kubectl
az aks install-cli

# Verify installations
az --version
kubectl version --client
```

### 2. Login to Azure

```bash
az login
az account set --subscription "<YOUR_SUBSCRIPTION_ID>"
```

### 3. Create Azure Container Registry (if not existing)

```bash
az acr create \
  --resource-group <RESOURCE_GROUP> \
  --name <ACR_NAME> \
  --sku Basic

# Attach ACR to AKS (allows AKS to pull images without separate credentials)
az aks update \
  --resource-group <RESOURCE_GROUP> \
  --name <CLUSTER_NAME> \
  --attach-acr <ACR_NAME>
```

---

## AKS Cluster Setup

### 1. Create AKS Cluster (if not existing)

```bash
az aks create \
  --resource-group <RESOURCE_GROUP> \
  --name <CLUSTER_NAME> \
  --node-count 2 \
  --node-vm-size Standard_D2s_v3 \
  --enable-addons monitoring \
  --generate-ssh-keys \
  --attach-acr <ACR_NAME>
```

### 2. Configure kubectl

```bash
az aks get-credentials \
  --resource-group <RESOURCE_GROUP> \
  --name <CLUSTER_NAME> \
  --overwrite-existing

# Verify connection
kubectl cluster-info
kubectl get nodes
```

### 3. Install Application Gateway Ingress Controller (AGIC)

```bash
# Enable AGIC add-on
az aks enable-addons \
  --resource-group <RESOURCE_GROUP> \
  --name <CLUSTER_NAME> \
  --addons ingress-appgw \
  --appgw-name resortslite-agw \
  --appgw-subnet-cidr "10.225.0.0/16"
```

---

## Kubernetes Deployment

### Automated Deployment (Recommended)

#### Linux / macOS

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

The script will prompt for:
- Azure Resource Group and AKS Cluster name
- Full Docker image URI
- All required environment variables (Oracle DB, Redis, service endpoints)

#### Windows

```cmd
scripts\deploy-image.bat
```

### Manual Deployment

#### 1. Update deployment.yaml

Replace all `{{PLACEHOLDER}}` values in `kubernetes/deployment.yaml`:

```bash
# Replace image URI
sed -i 's|{{IMAGE_URI}}|<ACR_NAME>.azurecr.io/resortslite:latest|g' kubernetes/deployment.yaml

# Replace database config
sed -i 's|{{SPRING_DATASOURCE_URL}}|jdbc:oracle:thin:@<DB_HOST>:1521:<SID>|g' kubernetes/deployment.yaml
sed -i 's|{{SPRING_DATASOURCE_USERNAME}}|<DB_USER>|g' kubernetes/deployment.yaml
sed -i 's|{{SPRING_DATASOURCE_PASSWORD}}|<DB_PASS>|g' kubernetes/deployment.yaml

# Replace Redis config
sed -i 's|{{REDIS_HOST}}|<REDIS_HOST>.redis.cache.windows.net|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_PORT}}|6380|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_PASSWORD}}|<REDIS_ACCESS_KEY>|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_SSL}}|true|g' kubernetes/deployment.yaml
```

#### 2. Apply Manifests

```bash
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml
```

#### 3. Verify Deployment

```bash
# Watch pod status
kubectl get pods -n resortslite -w

# Check deployment rollout
kubectl rollout status deployment/resortslite -n resortslite

# View all resources
kubectl get pods,svc,ingress -n resortslite

# View pod logs
kubectl logs -l app=resortslite -n resortslite --tail=100
```

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Default |
|---|---|---|
| `SPRING_DATASOURCE_URL` | Oracle JDBC connection URL | — |
| `SPRING_DATASOURCE_USERNAME` | Database username | — |
| `SPRING_DATASOURCE_PASSWORD` | Database password | — |
| `REDIS_HOST` | Azure Cache for Redis hostname | `localhost` |
| `REDIS_PORT` | Redis port (6380 for Azure SSL) | `6379` |
| `REDIS_PASSWORD` | Redis access key | — |
| `REDIS_SSL` | Enable SSL for Redis | `true` |
| `APP_PAYMENT_ENDPOINT` | Payment service URL | — |
| `APP_INVENTORY_ENDPOINT` | Inventory service URL | — |
| `APP_NOTIFICATION_ENDPOINT` | Notification service URL | — |
| `REPORT_BASE_PATH` | Report file base directory | `/var/reports` |
| `BACKUP_PATH` | Backup file directory | `/var/backups/nightly` |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` |
| `JAVA_OPTS` | JVM options | `-Xms256m -Xmx512m ...` |
| `TZ` | Timezone | `UTC` |

### Using Kubernetes Secrets (Recommended for Production)

```bash
# Create secret for database credentials
kubectl create secret generic resortslite-db-secret \
  --from-literal=url='jdbc:oracle:thin:@<HOST>:1521:<SID>' \
  --from-literal=username='<USER>' \
  --from-literal=password='<PASS>' \
  -n resortslite

# Create secret for Redis credentials
kubectl create secret generic resortslite-redis-secret \
  --from-literal=host='<REDIS_HOST>.redis.cache.windows.net' \
  --from-literal=password='<REDIS_ACCESS_KEY>' \
  -n resortslite
```

Then reference secrets in `deployment.yaml` using `secretKeyRef` instead of plain `value`.

---

## Health Checks and Monitoring

### Spring Boot Actuator Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Liveness and readiness probe |
| `GET /actuator/info` | Application information |

### Kubernetes Probes Configuration

The deployment is configured with:
- **Liveness Probe**: `GET /actuator/health` — starts after 60s, checks every 30s
- **Readiness Probe**: `GET /actuator/health` — starts after 30s, checks every 15s

> **Note**: JVM startup on Java 8 can take 30–60 seconds. The `initialDelaySeconds` values are set accordingly.

### View Health Status

```bash
# Port-forward to test locally
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite

# Check health
curl http://localhost:8080/actuator/health
```

---

## Scaling and Management

### Manual Scaling

```bash
# Scale to 3 replicas
kubectl scale deployment resortslite --replicas=3 -n resortslite

# Scale back to 2
kubectl scale deployment resortslite --replicas=2 -n resortslite
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

### Rolling Updates

```bash
# Update image
kubectl set image deployment/resortslite \
  resortslite=<ACR_NAME>.azurecr.io/resortslite:<NEW_TAG> \
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
kubectl rollout undo deployment/resortslite --to-revision=<N> -n resortslite
```

---

## Troubleshooting

### Pod Not Starting

```bash
# Describe pod for events
kubectl describe pod -l app=resortslite -n resortslite

# View container logs
kubectl logs -l app=resortslite -n resortslite --previous

# Check resource constraints
kubectl top pods -n resortslite
```

### Common Issues

**Issue**: `CrashLoopBackOff`  
**Cause**: Application failing to start — usually missing environment variables or DB/Redis connectivity  
**Fix**: Check logs with `kubectl logs`, verify all env vars are set correctly

**Issue**: `ImagePullBackOff`  
**Cause**: AKS cannot pull image from registry  
**Fix**: Ensure ACR is attached to AKS: `az aks update --attach-acr <ACR_NAME>`

**Issue**: `Readiness probe failed`  
**Cause**: Application not ready within `initialDelaySeconds`  
**Fix**: Increase `initialDelaySeconds` in deployment.yaml (Java 8 may need 60–90s)

**Issue**: Redis connection refused  
**Cause**: Wrong REDIS_HOST, REDIS_PORT, or REDIS_SSL setting  
**Fix**: For Azure Cache for Redis, use port `6380` and `REDIS_SSL=true`

**Issue**: Oracle DB connection timeout  
**Cause**: Network policy or firewall blocking DB access from AKS  
**Fix**: Ensure AKS subnet has network access to Oracle DB host/port

### Ingress Not Accessible

```bash
# Check ingress status
kubectl describe ingress resortslite-ingress -n resortslite

# Check AGIC logs
kubectl logs -l app=ingress-appgw -n kube-system --tail=50

# Get ingress IP
kubectl get ingress resortslite-ingress -n resortslite
```

---

## Security Considerations

1. **Never commit secrets** to source control — use Kubernetes Secrets or Azure Key Vault
2. **Use Azure Key Vault CSI Driver** to inject secrets as environment variables at runtime
3. **Enable Azure Workload Identity** for pod-level Azure resource access without credentials
4. **Use HTTPS** for all external endpoints — configure TLS on the ingress
5. **Run as non-root** — the Dockerfile creates and uses a non-root `appuser`
6. **Upgrade vulnerable dependencies** — `log4j-core:2.14.1` (CVE-2021-44228) and `commons-collections:3.2.1` (CVE-2015-6420) present in pom.xml should be upgraded immediately
7. **Rotate credentials** regularly — database passwords and Redis access keys
8. **Enable network policies** to restrict pod-to-pod communication
9. **Use private ACR** — ensure ACR is not publicly accessible
10. **Enable Azure Defender for Containers** for runtime threat detection

---

## Java-Specific Notes

- **JVM Startup Time**: Java 8 applications typically take 30–60 seconds to start. Kubernetes probes are configured with appropriate `initialDelaySeconds` to prevent premature restarts.
- **Container Memory**: The JVM is configured with `-XX:+UseContainerSupport` and `-XX:MaxRAMPercentage=75.0` to respect container memory limits automatically.
- **Heap Settings**: `-Xms256m -Xmx512m` — adjust based on actual workload. Monitor with `kubectl top pods`.
- **Spring Session + Redis**: The application uses Spring Session backed by Azure Cache for Redis. Ensure `REDIS_SSL=true` and port `6380` for Azure Cache for Redis.
- **Oracle JDBC**: The application uses `ojdbc8` for Java 8 compatibility. Ensure the Oracle DB is reachable from the AKS node subnet.
- **Graceful Shutdown**: `terminationGracePeriodSeconds: 30` allows in-flight requests to complete before pod termination.
