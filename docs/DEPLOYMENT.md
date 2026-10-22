# ResortsLite — Deployment Guide

## Overview

This guide covers building, containerizing, and deploying the **ResortsLite** Spring Boot application to **Azure Kubernetes Service (AKS)**.

- **Application**: ResortsLite — Legacy resort booking REST API
- **Framework**: Spring Boot 2.7.18
- **Java Version**: Java 8
- **Build Tool**: Maven
- **Port**: 8080
- **Health Endpoint**: `/actuator/health`
- **Target Platform**: Azure AKS

---

## Prerequisites

### Local Development
- Docker Desktop 24.x or later
- Java 8 JDK (eclipse-temurin:8 recommended)
- Apache Maven 3.9.x
- Git

### Azure AKS Deployment
- Azure CLI (`az`) 2.50+
- `kubectl` 1.27+
- Active Azure subscription
- Azure Container Registry (ACR) or Docker Hub account
- AKS cluster provisioned (see AKS Setup below)
- Azure Cache for Redis instance
- Oracle Database (or compatible JDBC endpoint)

---

## Project Structure

```
resortslite/
├── Dockerfile                  # Multi-stage Docker build
├── docker-compose.yml          # Local development compose file
├── .dockerignore               # Docker build exclusions
├── pom.xml                     # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
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
│   ├── deploy-image.sh         # Linux/macOS AKS deploy
│   └── deploy-image.bat        # Windows AKS deploy
└── docs/
    └── DEPLOYMENT.md           # This file
```

---

## Environment Variables

The application requires the following environment variables at runtime:

| Variable | Description | Example |
|---|---|---|
| `SPRING_DATASOURCE_URL` | Oracle JDBC connection URL | `jdbc:oracle:thin:@host:1521:SID` |
| `SPRING_DATASOURCE_USERNAME` | Database username | `admin` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | *(from Key Vault)* |
| `REDIS_HOST` | Azure Cache for Redis hostname | `myredis.redis.cache.windows.net` |
| `REDIS_PORT` | Redis TLS port | `6380` |
| `REDIS_PASSWORD` | Redis primary access key | *(from Key Vault)* |
| `PAYMENT_API_URL` | Payment service endpoint | `http://payment-service:9090/payments/charge` |
| `APP_PAYMENT_ENDPOINT` | Payment charge endpoint | `http://payment-svc:9090/charge` |
| `APP_INVENTORY_ENDPOINT` | Inventory service endpoint | `http://inventory-svc:8081/rooms` |
| `APP_NOTIFICATION_ENDPOINT` | Notification service endpoint | `http://notify-svc:7070/send` |
| `REPORT_BASE_PATH` | Report file base path | `/tmp/reports` |
| `BACKUP_PATH` | Backup file base path | `/tmp/backups` |

---

## Local Development with Docker Compose

### 1. Configure Environment

Create a `.env` file in the project root:

```env
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=yourpassword
REDIS_HOST=localhost
REDIS_PORT=6380
REDIS_PASSWORD=yourrediskey
PAYMENT_API_URL=http://payment-service:9090/payments/charge
APP_PAYMENT_ENDPOINT=http://payment-svc:9090/charge
APP_INVENTORY_ENDPOINT=http://inventory-svc:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://notify-svc:7070/send
REPORT_BASE_PATH=/tmp/reports
BACKUP_PATH=/tmp/backups
```

### 2. Build and Start

```bash
# Build and start the application container
docker-compose up --build

# Run in background
docker-compose up -d --build

# View logs
docker-compose logs -f resortslite

# Stop
docker-compose down
```

### 3. Verify

```bash
curl http://localhost:8080/actuator/health
```

Expected response:
```json
{"status":"UP"}
```

---

## Building the Docker Image

### Manual Build

```bash
# Build image
docker build -t resortslite:latest .

# Run locally
docker run -p 8080:8080 \
  -e SPRING_DATASOURCE_URL="jdbc:oracle:thin:@host:1521:SID" \
  -e SPRING_DATASOURCE_USERNAME="admin" \
  -e SPRING_DATASOURCE_PASSWORD="password" \
  -e REDIS_HOST="myredis.redis.cache.windows.net" \
  -e REDIS_PORT="6380" \
  -e REDIS_PASSWORD="rediskey" \
  resortslite:latest
```

### Using Build Scripts

**Linux/macOS:**
```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

**Windows:**
```cmd
scripts\build-push.bat
```

The script will prompt for:
1. Image tag (default: `latest`)
2. Registry type: Azure ACR or Docker Hub
3. Registry credentials

---

## Azure AKS Setup

### 1. Install Prerequisites

```bash
# Install Azure CLI
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash

# Install kubectl
az aks install-cli

# Login to Azure
az login
```

### 2. Create Resource Group (if needed)

```bash
az group create --name resortslite-rg --location eastus
```

### 3. Create AKS Cluster (if needed)

```bash
az aks create \
  --resource-group resortslite-rg \
  --name resortslite-aks \
  --node-count 2 \
  --node-vm-size Standard_DS2_v2 \
  --enable-addons monitoring \
  --generate-ssh-keys
```

### 4. Create Azure Container Registry (if needed)

```bash
az acr create \
  --resource-group resortslite-rg \
  --name resortsliteacr \
  --sku Basic

# Attach ACR to AKS
az aks update \
  --resource-group resortslite-rg \
  --name resortslite-aks \
  --attach-acr resortsliteacr
```

### 5. Create Azure Cache for Redis (if needed)

```bash
az redis create \
  --resource-group resortslite-rg \
  --name resortslite-redis \
  --location eastus \
  --sku Basic \
  --vm-size c0 \
  --enable-non-ssl-port false
```

### 6. Configure kubectl

```bash
az aks get-credentials \
  --resource-group resortslite-rg \
  --name resortslite-aks

# Verify connection
kubectl cluster-info
kubectl get nodes
```

---

## Kubernetes Deployment

### Option A: Using Deploy Scripts (Recommended)

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
- Azure Resource Group and AKS Cluster name
- Full Docker image URI
- All required environment variables

### Option B: Manual kubectl Deployment

#### Step 1: Build and Push Image

```bash
# Login to ACR
az acr login --name resortsliteacr

# Build and push
docker build -t resortsliteacr.azurecr.io/resortslite:latest .
docker push resortsliteacr.azurecr.io/resortslite:latest
```

#### Step 2: Update deployment.yaml

Replace all `{{PLACEHOLDER}}` values in `kubernetes/deployment.yaml`:

```bash
# Replace image URI
sed -i 's|{{IMAGE_URI}}|resortsliteacr.azurecr.io/resortslite:latest|g' kubernetes/deployment.yaml

# Replace database config
sed -i 's|{{SPRING_DATASOURCE_URL}}|jdbc:oracle:thin:@host:1521:SID|g' kubernetes/deployment.yaml
sed -i 's|{{SPRING_DATASOURCE_USERNAME}}|admin|g' kubernetes/deployment.yaml
sed -i 's|{{SPRING_DATASOURCE_PASSWORD}}|yourpassword|g' kubernetes/deployment.yaml

# Replace Redis config
sed -i 's|{{REDIS_HOST}}|myredis.redis.cache.windows.net|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_PORT}}|6380|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_PASSWORD}}|yourrediskey|g' kubernetes/deployment.yaml

# Replace service endpoints
sed -i 's|{{PAYMENT_API_URL}}|http://payment-service:9090/payments/charge|g' kubernetes/deployment.yaml
sed -i 's|{{APP_PAYMENT_ENDPOINT}}|http://payment-svc:9090/charge|g' kubernetes/deployment.yaml
sed -i 's|{{APP_INVENTORY_ENDPOINT}}|http://inventory-svc:8081/rooms|g' kubernetes/deployment.yaml
sed -i 's|{{APP_NOTIFICATION_ENDPOINT}}|http://notify-svc:7070/send|g' kubernetes/deployment.yaml
sed -i 's|{{REPORT_BASE_PATH}}|/tmp/reports|g' kubernetes/deployment.yaml
sed -i 's|{{BACKUP_PATH}}|/tmp/backups|g' kubernetes/deployment.yaml
```

#### Step 3: Apply Manifests

```bash
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml
```

#### Step 4: Verify Deployment

```bash
# Check rollout status
kubectl rollout status deployment/resortslite -n resortslite

# Check pods
kubectl get pods -n resortslite

# Check services and ingress
kubectl get svc,ingress -n resortslite

# View logs
kubectl logs -f deployment/resortslite -n resortslite
```

---

## Health Checks

The application exposes Spring Boot Actuator health endpoints:

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Overall health (liveness + readiness) |

Kubernetes probes are configured in `deployment.yaml`:
- **Liveness probe**: `/actuator/health` — restarts pod if unhealthy
- **Readiness probe**: `/actuator/health` — removes pod from load balancer if not ready
- **Initial delay**: 60s (liveness), 30s (readiness) — allows JVM startup time

---

## Scaling and Management

### Horizontal Scaling

```bash
# Scale to 3 replicas
kubectl scale deployment resortslite --replicas=3 -n resortslite

# Configure Horizontal Pod Autoscaler
kubectl autoscale deployment resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortslite
```

### Rolling Updates

```bash
# Update image
kubectl set image deployment/resortslite \
  resortslite=resortsliteacr.azurecr.io/resortslite:v2.0.0 \
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
kubectl logs <pod-name> -n resortslite --previous  # previous container logs
```

### Common Issues

**1. OOMKilled (Out of Memory)**
- Increase memory limits in `deployment.yaml`
- Adjust `JAVA_OPTS`: `-Xmx512m` → `-Xmx768m`

**2. Redis Connection Failure**
- Verify `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` environment variables
- Ensure Azure Cache for Redis firewall allows AKS node IPs
- Check SSL: `spring.redis.ssl=true` requires port 6380

**3. Database Connection Failure**
- Verify `SPRING_DATASOURCE_URL` is reachable from AKS pods
- Check Oracle DB firewall rules for AKS subnet
- Validate credentials

**4. Liveness Probe Failing**
- Increase `initialDelaySeconds` for slow JVM startup
- Check `/actuator/health` endpoint manually:
  ```bash
  kubectl exec -it <pod-name> -n resortslite -- wget -qO- http://localhost:8080/actuator/health
  ```

**5. Ingress Not Accessible**
- Verify Application Gateway Ingress Controller (AGIC) is installed
- Check ingress annotations: `kubernetes.io/ingress.class: azure/application-gateway`
- Verify ingress IP: `kubectl get ingress -n resortslite`

### Useful Diagnostic Commands

```bash
# Get all resources in namespace
kubectl get all -n resortslite

# Exec into running pod
kubectl exec -it <pod-name> -n resortslite -- /bin/sh

# Port-forward for local testing
kubectl port-forward deployment/resortslite 8080:8080 -n resortslite

# Check resource usage
kubectl top pods -n resortslite
kubectl top nodes
```

---

## Security Considerations

1. **Secrets Management**: Use Azure Key Vault with Secrets Store CSI Driver to inject sensitive values (DB password, Redis password) as Kubernetes secrets — never hardcode in manifests.

2. **Non-root Container**: The Dockerfile runs the application as a non-root user (`appuser`) for security.

3. **Network Policies**: Consider adding Kubernetes NetworkPolicy to restrict pod-to-pod communication.

4. **Image Scanning**: Enable Azure Defender for Containers or use `az acr check-health` to scan images for vulnerabilities.

5. **RBAC**: Use Kubernetes RBAC and Azure AD integration for cluster access control.

6. **TLS**: Configure TLS termination at the Application Gateway level for HTTPS traffic.

7. **Dependency Vulnerabilities**: The `pom.xml` contains known vulnerable dependencies (log4j 2.14.1 CVE-2021-44228, commons-collections 3.2.1 CVE-2015-6420). **Update these before production deployment.**

---

## Java-Specific Notes

### JVM Configuration

The application uses the following JVM flags (set via `JAVA_OPTS`):

```
-Xms256m                    # Initial heap size
-Xmx512m                    # Maximum heap size
-XX:+UseContainerSupport    # Enable container-aware JVM
-XX:MaxRAMPercentage=75.0   # Use 75% of container memory for heap
-Djava.security.egd=file:/dev/./urandom  # Faster random number generation
-Dfile.encoding=UTF-8       # Consistent character encoding
-Duser.timezone=UTC         # Consistent timezone
```

### Spring Boot Actuator

The application exposes `/actuator/health` for health monitoring. The management endpoint is configured in `application.properties`:

```properties
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=always
management.endpoints.web.base-path=/actuator
```

### Spring Session with Redis

The application uses Spring Session backed by Azure Cache for Redis for distributed session management across AKS pod replicas. This is configured via:
- `spring.session.store-type=redis`
- `@EnableRedisHttpSession` annotation on the main class
- Redis connection via `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` environment variables

### Distributed Cache

Booking data is cached in Azure Cache for Redis via `RedisTemplate<String, Object>` with a 1-hour TTL, ensuring cache consistency across all pod replicas during horizontal scale-out.
