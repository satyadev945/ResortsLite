# Deployment Guide — orcappdbmmono on Azure AKS

## Overview

This guide covers building, pushing, and deploying the **orcappdbmmono** (ResortsLite) Spring Boot application to **Azure Kubernetes Service (AKS)**.

- **Framework**: Spring Boot 2.7.x
- **Java Version**: 8
- **Build Tool**: Maven
- **Application Port**: 8080
- **Health Endpoint**: `/actuator/health`
- **Session Store**: Azure Cache for Redis (Spring Session)
- **Database**: Oracle (ojdbc8)

---

## Prerequisites

### Local Development
| Tool | Minimum Version | Purpose |
|------|----------------|---------|
| Docker Desktop | 24.x | Build and run containers |
| Java JDK | 8 | Local compilation |
| Maven | 3.8.x | Build tool |

### Azure AKS Deployment
| Tool | Minimum Version | Purpose |
|------|----------------|---------|
| Azure CLI (`az`) | 2.50+ | AKS authentication |
| kubectl | 1.27+ | Kubernetes management |
| Docker | 24.x | Image build and push |
| Azure Subscription | — | AKS cluster, ACR, Redis |

Install Azure CLI: https://docs.microsoft.com/en-us/cli/azure/install-azure-cli  
Install kubectl: `az aks install-cli`

---

## Project Structure

```
orcappdbmmono/
├── Dockerfile                  # Multi-stage build (Java 8)
├── docker-compose.yml          # Local development (app only)
├── .dockerignore               # Excludes build artefacts and wrappers
├── kubernetes/
│   ├── namespace.yaml
│   ├── deployment.yaml         # 2 replicas, liveness/readiness probes
│   ├── service.yaml            # ClusterIP on port 80 → 8080
│   └── ingress.yaml            # Azure Application Gateway Ingress
├── scripts/
│   ├── build-push.sh           # Linux/macOS: build & push image
│   ├── build-push.bat          # Windows: build & push image
│   ├── deploy-image.sh         # Linux/macOS: deploy to AKS
│   └── deploy-image.bat        # Windows: deploy to AKS
└── docs/
    └── DEPLOYMENT.md           # This file
```

---

## 1. Local Development with Docker Compose

### 1.1 Configure Environment Variables

Create a `.env` file in the project root (never commit this file):

```dotenv
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@<DB_HOST>:1521:<DB_SID>
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=<your-db-password>

REDIS_HOST=<your-redis-host>.redis.cache.windows.net
REDIS_PORT=6380
REDIS_PASSWORD=<your-redis-access-key>
REDIS_SSL=true

PAYMENT_API_URL=http://payment-service:9090/payments/charge
APP_PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge
APP_INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://notify.internal:7070/send

REPORTS_BASE_PATH=/var/reports
REPORTS_BACKUP_PATH=/var/backups/nightly
```

### 1.2 Build and Start

```bash
# Build the image and start the container
docker-compose up --build

# Run in background
docker-compose up -d --build

# View logs
docker-compose logs -f orcappdbmmono

# Stop
docker-compose down
```

### 1.3 Verify Health

```bash
curl http://localhost:8080/actuator/health
```

Expected response:
```json
{"status":"UP"}
```

---

## 2. Build and Push Docker Image

### 2.1 Linux / macOS

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

The script will prompt you to:
1. Select registry type (Azure ACR or Docker Hub)
2. Enter registry credentials
3. Enter an image tag (defaults to `latest`)

### 2.2 Windows

```cmd
scripts\build-push.bat
```

### 2.3 Manual Build (ACR example)

```bash
# Login to ACR
az acr login --name <ACR_NAME>

# Build
docker build -t <ACR_NAME>.azurecr.io/orcappdbmmono:1.0.0 .

# Push
docker push <ACR_NAME>.azurecr.io/orcappdbmmono:1.0.0
```

---

## 3. Azure AKS Setup

### 3.1 Create AKS Cluster (if not existing)

```bash
# Login to Azure
az login

# Create resource group
az group create --name rg-orcappdbmmono --location eastus

# Create AKS cluster
az aks create \
  --resource-group rg-orcappdbmmono \
  --name aks-orcappdbmmono \
  --node-count 2 \
  --node-vm-size Standard_DS2_v2 \
  --enable-addons monitoring \
  --generate-ssh-keys

# Get credentials
az aks get-credentials --resource-group rg-orcappdbmmono --name aks-orcappdbmmono
```

### 3.2 Attach ACR to AKS

```bash
az aks update \
  --resource-group rg-orcappdbmmono \
  --name aks-orcappdbmmono \
  --attach-acr <ACR_NAME>
```

### 3.3 Install Application Gateway Ingress Controller (AGIC)

```bash
# Enable AGIC add-on
az aks enable-addons \
  --resource-group rg-orcappdbmmono \
  --name aks-orcappdbmmono \
  --addons ingress-appgw \
  --appgw-name appgw-orcappdbmmono \
  --appgw-subnet-cidr "10.225.0.0/16"
```

---

## 4. Deploy to AKS

### 4.1 Linux / macOS

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

The script will prompt for:
- Azure Resource Group and AKS Cluster name
- Full Docker image URI (e.g. `myregistry.azurecr.io/orcappdbmmono:1.0.0`)
- All required environment variables (Oracle DB, Redis, service endpoints)

### 4.2 Windows

```cmd
scripts\deploy-image.bat
```

### 4.3 Manual Deployment

```bash
# 1. Configure kubectl
az aks get-credentials --resource-group rg-orcappdbmmono --name aks-orcappdbmmono

# 2. Apply manifests in order
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

# 3. Wait for rollout
kubectl rollout status deployment/orcappdbmmono -n orcappdbmmono

# 4. Verify
kubectl get pods,svc,ingress -n orcappdbmmono
```

---

## 5. Kubernetes Manifest Reference

### namespace.yaml
Creates the `orcappdbmmono` namespace to isolate all application resources.

### deployment.yaml
- **Replicas**: 2 (horizontal scaling)
- **Image**: `{{IMAGE_URI}}` — replaced by deploy script
- **Resources**: requests `250m CPU / 512Mi RAM`, limits `500m CPU / 1Gi RAM`
- **Liveness probe**: `GET /actuator/health` — initial delay 60s, period 30s
- **Readiness probe**: `GET /actuator/health` — initial delay 30s, period 15s
- **JVM flags**: `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Xms256m -Xmx512m`

### service.yaml
- **Type**: ClusterIP
- **Port mapping**: 80 → 8080

### ingress.yaml
- **Class**: `azure/application-gateway`
- **Host**: `orcappdbmmono.example.com` (update to your actual domain)
- **SSL redirect**: enabled

---

## 6. Configuration Management

### Environment Variables Reference

| Variable | Description | Example |
|----------|-------------|---------|
| `SPRING_DATASOURCE_URL` | Oracle JDBC connection string | `jdbc:oracle:thin:@host:1521:SID` |
| `SPRING_DATASOURCE_USERNAME` | Database username | `admin` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | (from Key Vault) |
| `REDIS_HOST` | Azure Cache for Redis hostname | `myredis.redis.cache.windows.net` |
| `REDIS_PORT` | Redis port | `6380` |
| `REDIS_PASSWORD` | Redis access key | (from Key Vault) |
| `REDIS_SSL` | Enable TLS for Redis | `true` |
| `PAYMENT_API_URL` | Payment service URL | `http://payment-service:9090/payments/charge` |
| `APP_PAYMENT_ENDPOINT` | Payment endpoint | `http://payment-svc:9090/charge` |
| `APP_INVENTORY_ENDPOINT` | Inventory service URL | `http://inventory-svc:8081/rooms` |
| `APP_NOTIFICATION_ENDPOINT` | Notification service URL | `http://notify:7070/send` |
| `REPORTS_BASE_PATH` | Reports directory | `/var/reports` |
| `REPORTS_BACKUP_PATH` | Backup directory | `/var/backups/nightly` |

### Using Azure Key Vault with Secrets Store CSI Driver

```bash
# Install Secrets Store CSI Driver
az aks enable-addons \
  --addons azure-keyvault-secrets-provider \
  --resource-group rg-orcappdbmmono \
  --name aks-orcappdbmmono

# Create SecretProviderClass to mount DB and Redis secrets
# (see Azure documentation for full SecretProviderClass YAML)
```

---

## 7. Scaling and Management

### Horizontal Pod Autoscaler

```bash
kubectl autoscale deployment orcappdbmmono \
  --namespace orcappdbmmono \
  --cpu-percent=70 \
  --min=2 \
  --max=10
```

### Rolling Update

```bash
# Update image
kubectl set image deployment/orcappdbmmono \
  orcappdbmmono=<NEW_IMAGE_URI> \
  -n orcappdbmmono

# Monitor rollout
kubectl rollout status deployment/orcappdbmmono -n orcappdbmmono
```

### Rollback

```bash
kubectl rollout undo deployment/orcappdbmmono -n orcappdbmmono

# Rollback to specific revision
kubectl rollout undo deployment/orcappdbmmono -n orcappdbmmono --to-revision=2
```

---

## 8. Troubleshooting

### Pod Not Starting

```bash
# Check pod status
kubectl get pods -n orcappdbmmono

# Describe pod for events
kubectl describe pod <POD_NAME> -n orcappdbmmono

# View logs
kubectl logs <POD_NAME> -n orcappdbmmono
kubectl logs <POD_NAME> -n orcappdbmmono --previous
```

### Common Issues

| Symptom | Likely Cause | Resolution |
|---------|-------------|------------|
| `CrashLoopBackOff` | JVM OOM or DB connection failure | Check `SPRING_DATASOURCE_*` env vars; increase memory limits |
| `ImagePullBackOff` | ACR not attached to AKS | Run `az aks update --attach-acr <ACR_NAME>` |
| Readiness probe failing | App slow to start (JVM warm-up) | Increase `initialDelaySeconds` in deployment.yaml |
| Redis connection refused | Wrong `REDIS_HOST` or `REDIS_SSL` | Verify Azure Cache for Redis hostname and SSL setting |
| `ORA-12541` | Oracle DB unreachable | Check `SPRING_DATASOURCE_URL` and network connectivity |

### Health Check

```bash
# Port-forward for local testing
kubectl port-forward svc/orcappdbmmono-service 8080:80 -n orcappdbmmono

# Test health endpoint
curl http://localhost:8080/actuator/health
```

### Resource Usage

```bash
kubectl top pods -n orcappdbmmono
kubectl top nodes
```

---

## 9. Security Considerations

1. **Never commit secrets** — use Azure Key Vault with Secrets Store CSI Driver
2. **Non-root container** — the Dockerfile creates and uses `appuser` (non-root)
3. **Network policies** — restrict pod-to-pod traffic with Kubernetes NetworkPolicy
4. **Image scanning** — enable Azure Defender for Containers to scan ACR images
5. **RBAC** — use Azure AD Workload Identity for pod-level Azure resource access
6. **TLS** — configure TLS certificates on the Application Gateway Ingress
7. **Dependency vulnerabilities** — the pom.xml contains known CVEs (log4j 2.14.1, commons-collections 3.2.1); upgrade before production deployment

---

## 10. Java-Specific Notes

- **JVM Container Support**: `-XX:+UseContainerSupport` ensures the JVM respects container memory limits rather than host memory
- **MaxRAMPercentage**: Set to 75% so the JVM heap uses up to 75% of the container memory limit (1Gi → ~768Mi heap)
- **Spring Session + Redis**: All HTTP sessions are stored in Azure Cache for Redis, enabling stateless horizontal scaling without sticky sessions
- **Actuator**: Only the `/actuator/health` endpoint is exposed (configured in `application.properties`)
- **Graceful Shutdown**: `terminationGracePeriodSeconds: 30` allows in-flight requests to complete before pod termination
- **Oracle JDBC**: Requires `SPRING_DATASOURCE_URL` to point to a reachable Oracle instance; the H2 in-memory database is available as a fallback for local testing
