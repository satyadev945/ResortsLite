# ResortsLite — Deployment Guide

## Overview

This guide covers building, containerizing, and deploying the **ResortsLite** Spring Boot application (Java 8) to **AWS EKS (Elastic Kubernetes Service)**.

- **Framework**: Spring Boot 2.7.x
- **Java Version**: 8 (Amazon Corretto 8 runtime)
- **Build Tool**: Maven
- **Application Port**: 8080
- **Health Endpoint**: `/actuator/health`
- **Target Platform**: AWS EKS

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker | 20.x+ | Build and run containers |
| Java JDK | 8+ | Local development |
| Maven | 3.8+ | Build tool |

### AWS EKS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS authentication and ECR |
| kubectl | 1.27+ | Kubernetes cluster management |
| eksctl | 0.150+ | EKS cluster provisioning (optional) |

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
Test rem options for Oracle db/
├── Dockerfile                    # Multi-stage Docker build
├── docker-compose.yml            # Local development compose
├── .dockerignore                 # Docker build exclusions
├── pom.xml                       # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   ├── RedisConfig.java
│       │   ├── ReportService.java
│       │   └── entity/Doctor.java
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml
│   ├── deployment.yaml
│   ├── service.yaml
│   └── ingress.yaml
├── scripts/
│   ├── build-push.sh             # Linux/macOS build & push
│   ├── build-push.bat            # Windows build & push
│   ├── deploy-image.sh           # Linux/macOS EKS deploy
│   └── deploy-image.bat          # Windows EKS deploy
└── docs/
    └── DEPLOYMENT.md             # This file
```

---

## Environment Variables

The application requires the following environment variables. These must be provided via Kubernetes Secrets, ConfigMaps, or EKS Pod spec environment variables.

### Required — Oracle Database
| Variable | Description | Example |
|----------|-------------|---------|
| `SPRING_DATASOURCE_URL` | Oracle JDBC connection URL | `jdbc:oracle:thin:@db.example.com:1521:ORCL` |
| `SPRING_DATASOURCE_USERNAME` | Database username | `admin` |
| `SPRING_DATASOURCE_PASSWORD` | Database password (use Secret) | `<secret>` |

### Required — Redis / ElastiCache
| Variable | Description | Default |
|----------|-------------|---------|
| `REDIS_HOST` | Redis/ElastiCache hostname | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis auth password (use Secret) | `` |
| `REDIS_SSL` | Enable TLS for Redis | `false` |

### Optional — Internal Service Endpoints
| Variable | Description | Default |
|----------|-------------|---------|
| `PAYMENT_API_URL` | Payment service URL | `http://payment-service.default.svc.cluster.local:9090/payments/charge` |
| `APP_PAYMENT_ENDPOINT` | Payment endpoint | `http://payment-svc:9090/charge` |
| `APP_INVENTORY_ENDPOINT` | Inventory service URL | `http://inventory-svc:8081/rooms` |
| `APP_NOTIFICATION_ENDPOINT` | Notification service URL | `http://notify-svc:7070/send` |

### Optional — File Paths
| Variable | Description | Default |
|----------|-------------|---------|
| `REPORT_BASE_PATH` | Report storage path | `/var/reports` |
| `BACKUP_PATH` | Backup storage path | `/var/backups/nightly` |

---

## Local Development with Docker Compose

### Step 1: Configure Environment

Create a `.env` file in the project root:

```env
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=yourpassword
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_SSL=false
PAYMENT_API_URL=http://payment-service:9090/payments/charge
APP_PAYMENT_ENDPOINT=http://payment-svc:9090/charge
APP_INVENTORY_ENDPOINT=http://inventory-svc:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://notify-svc:7070/send
REPORT_BASE_PATH=/var/reports
BACKUP_PATH=/var/backups/nightly
```

### Step 2: Build and Start

```bash
# Build and start the application
docker-compose up --build

# Run in background
docker-compose up -d --build

# View logs
docker-compose logs -f resortslite

# Stop
docker-compose down
```

### Step 3: Verify

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=STANDARD&checkIn=2024-01-01&checkOut=2024-01-05"
```

---

## Building and Pushing the Docker Image

### Linux / macOS

```bash
# Make script executable
chmod +x scripts/build-push.sh

# Run from project root
./scripts/build-push.sh
```

The script will prompt for:
1. Image tag (default: `latest`)
2. Registry type (AWS ECR or Docker Hub)
3. Registry credentials

### Windows

```cmd
scripts\build-push.bat
```

### Manual Build

```bash
# Build image
docker build -t resortslite:latest .

# Tag for ECR
docker tag resortslite:latest <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/resortslite:latest

# Login to ECR
aws ecr get-login-password --region <REGION> | docker login --username AWS --password-stdin <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com

# Push
docker push <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/resortslite:latest
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
aws eks update-kubeconfig --region us-east-1 --name <CLUSTER_NAME>
kubectl cluster-info
```

### Step 3: Install AWS Load Balancer Controller

The ingress uses the AWS Load Balancer Controller. Install it on your EKS cluster:

```bash
# Create IAM policy
curl -O https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v2.6.0/docs/install/iam_policy.json
aws iam create-policy \
  --policy-name AWSLoadBalancerControllerIAMPolicy \
  --policy-document file://iam_policy.json

# Install via Helm
helm repo add eks https://aws.github.io/eks-charts
helm repo update
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=<CLUSTER_NAME> \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Step 4: Create Kubernetes Secrets

```bash
# Database credentials
kubectl create secret generic resortslite-db-secret \
  --namespace resortslite \
  --from-literal=db-username=<DB_USERNAME> \
  --from-literal=db-password=<DB_PASSWORD>

# Redis credentials
kubectl create secret generic resortslite-redis-secret \
  --namespace resortslite \
  --from-literal=redis-password=<REDIS_PASSWORD>
```

> **Note**: Create the namespace first before creating secrets (see Step 5).

### Step 5: Deploy Using Script

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
- Application environment variables

### Step 6: Manual Deployment (Alternative)

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

## Kubernetes Manifest Descriptions

| File | Kind | Description |
|------|------|-------------|
| `namespace.yaml` | Namespace | Creates `resortslite` namespace |
| `deployment.yaml` | Deployment | 2 replicas, resource limits, health probes |
| `service.yaml` | Service | ClusterIP service on port 80 → 8080 |
| `ingress.yaml` | Ingress | AWS ALB internet-facing ingress |

### Resource Limits

```yaml
resources:
  requests:
    cpu: "250m"
    memory: "512Mi"
  limits:
    cpu: "500m"
    memory: "1Gi"
```

### Health Probes

- **Liveness**: `GET /actuator/health` — initial delay 60s, period 30s
- **Readiness**: `GET /actuator/health` — initial delay 30s, period 15s

---

## Scaling

### Horizontal Pod Autoscaler

```bash
kubectl autoscale deployment resortslite \
  --namespace resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10
```

### Manual Scaling

```bash
kubectl scale deployment resortslite --replicas=4 -n resortslite
```

---

## Rolling Updates

```bash
# Update image
kubectl set image deployment/resortslite \
  resortslite=<NEW_IMAGE_URI> \
  -n resortslite

# Monitor rollout
kubectl rollout status deployment/resortslite -n resortslite

# Rollback if needed
kubectl rollout undo deployment/resortslite -n resortslite

# View rollout history
kubectl rollout history deployment/resortslite -n resortslite
```

---

## Troubleshooting

### Pod Not Starting

```bash
# Check pod status
kubectl get pods -n resortslite

# Describe pod for events
kubectl describe pod <POD_NAME> -n resortslite

# View pod logs
kubectl logs <POD_NAME> -n resortslite

# View previous container logs (if crashed)
kubectl logs <POD_NAME> -n resortslite --previous
```

### Common Issues

| Issue | Cause | Solution |
|-------|-------|---------|
| `CrashLoopBackOff` | App fails to start | Check logs; verify DB/Redis connectivity |
| `ImagePullBackOff` | Cannot pull image | Verify ECR permissions and image URI |
| `OOMKilled` | Out of memory | Increase memory limits in deployment.yaml |
| `Pending` pods | Insufficient cluster resources | Scale up node group |
| Ingress not resolving | ALB controller not installed | Install AWS Load Balancer Controller |
| DB connection refused | Wrong SPRING_DATASOURCE_URL | Verify Oracle endpoint and security groups |
| Redis connection refused | Wrong REDIS_HOST | Verify ElastiCache endpoint and security groups |

### Connectivity Debugging

```bash
# Exec into pod
kubectl exec -it <POD_NAME> -n resortslite -- /bin/sh

# Check service endpoints
kubectl get endpoints -n resortslite

# Check ingress status
kubectl describe ingress resortslite-ingress -n resortslite
```

---

## Security Considerations

1. **Never commit secrets** to source control — use Kubernetes Secrets or AWS Secrets Manager
2. **Oracle credentials** must be stored in Kubernetes Secrets (`resortslite-db-secret`)
3. **Redis password** must be stored in Kubernetes Secrets (`resortslite-redis-secret`)
4. **Enable Redis TLS** (`REDIS_SSL=true`) for ElastiCache in production
5. **Use HTTPS** — the ingress is configured to redirect HTTP → HTTPS (port 443)
6. **Non-root container** — the application runs as `appuser` (non-root)
7. **Log4j vulnerability** — `log4j-core:2.14.1` in pom.xml has CVE-2021-44228 (Log4Shell); upgrade to `2.17.1+` immediately
8. **commons-collections** — `3.2.1` has CVE-2015-6420; upgrade to `3.2.2+`
9. **SQL injection** — `BookingService.java` uses string concatenation for SQL; migrate to parameterized queries

---

## Java-Specific Configuration

### JVM Options

The container uses these JVM flags (set via `JAVA_OPTS`):

```
-Xmx512m                    # Maximum heap size
-Xms256m                    # Initial heap size
-XX:+UseContainerSupport    # Enable container-aware JVM
-XX:MaxRAMPercentage=75.0   # Use 75% of container memory for heap
-Djava.security.egd=file:/dev/./urandom  # Faster random number generation
-Dfile.encoding=UTF-8
-Duser.timezone=UTC
```

### Spring Profiles

Set `SPRING_PROFILES_ACTIVE=docker` for containerized deployments. Create `application-docker.properties` to override any local settings.

### Actuator Endpoints

Available endpoints (configured in `application.properties`):
- `GET /actuator/health` — Application health status
- `GET /actuator/info` — Application information

---

## Configuration Management

### Using AWS Parameter Store

```bash
# Store database URL
aws ssm put-parameter \
  --name "/resortslite/prod/db-url" \
  --value "jdbc:oracle:thin:@db.example.com:1521:ORCL" \
  --type "SecureString"
```

### Using AWS Secrets Manager

```bash
# Store database credentials
aws secretsmanager create-secret \
  --name "resortslite/prod/db-credentials" \
  --secret-string '{"username":"admin","password":"yourpassword"}'
```

Consider using the [AWS Secrets and Configuration Provider (ASCP)](https://docs.aws.amazon.com/secretsmanager/latest/userguide/integrating_csi_driver.html) to mount secrets directly into pods.

---

## Support

For issues with this deployment:
1. Check pod logs: `kubectl logs <POD_NAME> -n resortslite`
2. Verify environment variables are set correctly
3. Ensure Oracle DB and Redis/ElastiCache are accessible from the EKS VPC
4. Confirm AWS Load Balancer Controller is installed for ingress to work
