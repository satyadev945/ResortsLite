# ResortsLite — Deployment Guide

## Overview

This guide covers building, containerizing, and deploying the **ResortsLite** Spring Boot application (Java 8, Maven) to **Azure Kubernetes Service (AKS)**.

- **Framework**: Spring Boot 2.7.18
- **Java Version**: 8
- **Build Tool**: Maven
- **Application Port**: 8080
- **Health Endpoint**: `/actuator/health`
- **Session Store**: Azure Cache for Redis (Spring Session)
- **Database**: Oracle (via JDBC)

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Java JDK | 8+ | Local build/run |
| Maven | 3.8+ | Build tool |
| Docker | 20.10+ | Container build/run |
| Docker Compose | 2.x | Local multi-container orchestration |

### Azure AKS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| Azure CLI | 2.50+ | Azure resource management |
| kubectl | 1.27+ | Kubernetes cluster management |
| Azure Subscription | — | AKS, ACR, Azure Cache for Redis |

---

## Project Structure

```
.
├── Dockerfile                  # Multi-stage Docker build
├── docker-compose.yml          # Local development compose file
├── .dockerignore               # Docker build exclusions
├── pom.xml                     # Maven build descriptor
├── src/                        # Application source code
├── kubernetes/
│   ├── namespace.yaml          # Kubernetes namespace
│   ├── deployment.yaml         # Application deployment
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
SPRING_DATASOURCE_PASSWORD=your_db_password

# Azure Cache for Redis
REDIS_HOST=your-cache.redis.cache.windows.net
REDIS_PORT=6380
REDIS_PASSWORD=your_redis_access_key
REDIS_SSL=true

# External Services
PAYMENT_API_URL=http://payment-service:9090/payments/charge

# File Paths
REPORT_BASE_PATH=/var/reports
BACKUP_PATH=/var/backups/nightly

# Cache TTL
BOOKING_CACHE_TTL_SECONDS=3600
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

### 3. Verify Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# API endpoints
curl http://localhost:8080/api/bookings/availability?roomType=SUITE
```

---

## Building and Pushing the Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

### Windows

```cmd
scripts\build-push.bat
```

The script will prompt you to:
1. Select registry type (Azure ACR or Docker Hub)
2. Enter registry credentials
3. Specify an image tag (defaults to `latest`)

The script automatically sanitizes the image name to be Docker-compliant (lowercase, hyphens only).

---

## Azure AKS Deployment

### Step 1: Set Up Azure Resources

```bash
# Login to Azure
az login

# Create a resource group (if needed)
az group create --name rg-resortslite --location eastus

# Create Azure Container Registry
az acr create --resource-group rg-resortslite \
              --name resortsliteacr \
              --sku Basic

# Create AKS cluster (if needed)
az aks create \
  --resource-group rg-resortslite \
  --name aks-resortslite \
  --node-count 2 \
  --enable-addons monitoring \
  --attach-acr resortsliteacr \
  --generate-ssh-keys

# Install Application Gateway Ingress Controller (AGIC)
az aks enable-addons \
  --resource-group rg-resortslite \
  --name aks-resortslite \
  --addons ingress-appgw \
  --appgw-name agw-resortslite \
  --appgw-subnet-cidr "10.225.0.0/16"
```

### Step 2: Build and Push Image to ACR

```bash
# Build and push (select ACR option when prompted)
./scripts/build-push.sh
# Enter ACR name: resortsliteacr
# Enter image tag: 1.0.0
```

### Step 3: Deploy to AKS

#### Linux / macOS

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

#### Windows

```cmd
scripts\deploy-image.bat
```

The deploy script will prompt for:
- Azure Resource Group name
- AKS Cluster name
- Full Docker image URI (e.g., `resortsliteacr.azurecr.io/resortslite:1.0.0`)
- All required environment variables (database, Redis, service endpoints)

### Step 4: Verify Deployment

```bash
# Check pods
kubectl get pods -n resortslite

# Check services
kubectl get svc -n resortslite

# Check ingress
kubectl get ingress -n resortslite

# View pod logs
kubectl logs -l app=resortslite -n resortslite --tail=100

# Health check via port-forward
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite
curl http://localhost:8080/actuator/health
```

---

## Kubernetes Manifest Descriptions

### namespace.yaml
Creates the `resortslite` namespace to isolate all application resources.

### deployment.yaml
Deploys 2 replicas of the ResortsLite container with:
- **Image**: Pulled from your registry (placeholder `{{IMAGE_URI}}` replaced by deploy script)
- **Resources**: 250m CPU / 512Mi memory (requests), 500m CPU / 1Gi memory (limits)
- **Liveness Probe**: `GET /actuator/health` — restarts pod if unhealthy (initial delay: 60s)
- **Readiness Probe**: `GET /actuator/health` — removes pod from load balancer if not ready (initial delay: 30s)
- **Environment Variables**: All externalized via deploy script prompts

### service.yaml
ClusterIP service exposing port 80 → container port 8080.

### ingress.yaml
Azure Application Gateway Ingress Controller (AGIC) rule routing `resortslite.example.com` to the service.
Update the `host` field to your actual domain before deploying.

---

## Configuration Management

### Environment Variables Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `SERVER_PORT` | `8080` | Application HTTP port |
| `SPRING_DATASOURCE_URL` | — | Oracle JDBC connection URL |
| `SPRING_DATASOURCE_USERNAME` | — | Database username |
| `SPRING_DATASOURCE_PASSWORD` | — | Database password |
| `REDIS_HOST` | `localhost` | Azure Cache for Redis hostname |
| `REDIS_PORT` | `6380` | Redis TLS port |
| `REDIS_PASSWORD` | — | Redis access key |
| `REDIS_SSL` | `true` | Enable TLS for Redis |
| `PAYMENT_API_URL` | `http://payment-service:9090/payments/charge` | Payment service endpoint |
| `REPORT_BASE_PATH` | `/var/reports` | Report file storage path |
| `BACKUP_PATH` | `/var/backups/nightly` | Backup file storage path |
| `BOOKING_CACHE_TTL_SECONDS` | `3600` | Redis booking cache TTL |
| `BOOKING_CACHE_TTL_SECONDS` | `3600` | Redis booking cache TTL |
| `JAVA_OPTS` | See Dockerfile | JVM startup options |

### Using Azure Key Vault for Secrets

For production, store sensitive values (database password, Redis password) in Azure Key Vault and inject them using the Secrets Store CSI Driver:

```bash
# Install CSI driver
az aks enable-addons \
  --addons azure-keyvault-secrets-provider \
  --name aks-resortslite \
  --resource-group rg-resortslite
```

---

## Scaling and Management

### Horizontal Scaling

```bash
# Scale to 4 replicas
kubectl scale deployment resortslite --replicas=4 -n resortslite

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
  resortslite=resortsliteacr.azurecr.io/resortslite:2.0.0 \
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
- Missing environment variables (database URL, Redis credentials)
- Image pull errors (check ACR permissions: `az aks update --attach-acr`)
- Insufficient resources (check node capacity: `kubectl describe nodes`)

### JVM Startup Slow / Liveness Probe Failing

Spring Boot with JPA and Redis initialization can take 30–60 seconds. If liveness probes fail:

```yaml
# Increase initialDelaySeconds in deployment.yaml
livenessProbe:
  initialDelaySeconds: 90   # Increase from 60
  periodSeconds: 30
  failureThreshold: 5       # Increase tolerance
```

### Redis Connection Errors

```bash
# Verify Redis env vars are set
kubectl exec -it $(kubectl get pod -l app=resortslite -n resortslite -o jsonpath='{.items[0].metadata.name}') \
  -n resortslite -- env | grep REDIS
```

Ensure `REDIS_SSL=true` and `REDIS_PORT=6380` for Azure Cache for Redis.

### Database Connection Errors

Verify the Oracle JDBC URL is reachable from within the AKS cluster. Use a network policy or VNet peering if the database is in a private subnet.

### Ingress Not Routing Traffic

```bash
# Check AGIC logs
kubectl logs -n kube-system -l app=ingress-azure

# Verify Application Gateway health
az network application-gateway show \
  --resource-group rg-resortslite \
  --name agw-resortslite \
  --query "backendAddressPools"
```

---

## Security Considerations

1. **Never commit secrets** to source control — use Azure Key Vault + CSI Driver
2. **Use non-root container user** — already configured in Dockerfile (`appuser`)
3. **Enable network policies** to restrict pod-to-pod communication
4. **Use HTTPS** — configure TLS termination at the Application Gateway
5. **Rotate Redis access keys** regularly via Azure Portal
6. **Address security violations** noted in the source code:
   - Replace MD5 hashing with SHA-256 or bcrypt
   - Use parameterized SQL queries to prevent SQL injection
   - Upgrade `log4j-core` from 2.14.1 (CVE-2021-44228 Log4Shell) to 2.17.2+
   - Upgrade `commons-collections` from 3.2.1 (CVE-2015-6420) to 3.2.2+

---

## Java-Specific Notes

### JVM Memory Configuration

The Dockerfile sets the following JVM flags:
```
-XX:+UseContainerSupport       # Respect container memory limits
-XX:MaxRAMPercentage=75.0      # Use 75% of container memory for heap
-Xms256m                       # Initial heap size
-Xmx512m                       # Maximum heap size
-Djava.security.egd=file:/dev/./urandom  # Faster SecureRandom
```

With a 1Gi memory limit, the JVM will use up to ~768Mi for heap.

### Spring Boot Actuator

The application exposes:
- `GET /actuator/health` — liveness and readiness probe endpoint
- `GET /actuator/info` — application information

### Spring Session with Redis

Session data is stored in Azure Cache for Redis, enabling:
- Stateless horizontal scaling across AKS pods
- Session persistence across container restarts
- Zero-downtime rolling deployments

Configure session timeout via `SESSION_TIMEOUT_SECONDS` environment variable (default: 1800 seconds / 30 minutes).
