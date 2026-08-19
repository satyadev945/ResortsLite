# ResortsLite — GCP GKE Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Building and Pushing the Docker Image](#building-and-pushing-the-docker-image)
6. [GCP GKE Deployment](#gcp-gke-deployment)
7. [Kubernetes Manifest Reference](#kubernetes-manifest-reference)
8. [Configuration and Environment Variables](#configuration-and-environment-variables)
9. [GKE Scaling and Management](#gke-scaling-and-management)
10. [Troubleshooting](#troubleshooting)
11. [Security Considerations](#security-considerations)
12. [Java-Specific Notes](#java-specific-notes)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.18  
**Java Version**: 8  
**Build Tool**: Maven  
**Target Platform**: GCP GKE (Google Kubernetes Engine)  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

ResortsLite is a Spring Boot REST API for resort booking management. It uses:
- **Redis** for distributed session storage (Spring Session Data Redis) and booking cache
- **Google Cloud Pub/Sub** for asynchronous booking and report event publishing
- **JWT** for stateless authentication (signing key from GCP Secret Manager)
- **H2** in-memory database for local development

---

## Prerequisites

### Local Development
- Docker Desktop 24.x or later
- Docker Compose v2.x or later
- Java 8 JDK (for local builds outside Docker)
- Maven 3.8.x or later

### GCP GKE Deployment
- [Google Cloud SDK (gcloud CLI)](https://cloud.google.com/sdk/docs/install) — authenticated and configured
- [kubectl](https://kubernetes.io/docs/tasks/tools/) v1.27 or later
- A GCP project with the following APIs enabled:
  - Kubernetes Engine API
  - Artifact Registry API
  - Cloud Pub/Sub API
  - Secret Manager API
- A GKE cluster (Standard or Autopilot) already provisioned
- An Artifact Registry Docker repository (or Docker Hub account)

---

## Project Structure

```
Ref/
├── Dockerfile                  # Multi-stage Docker build
├── docker-compose.yml          # Local development (app only)
├── .dockerignore               # Files excluded from Docker context
├── pom.xml                     # Maven build descriptor
├── src/                        # Java source code
├── kubernetes/
│   ├── namespace.yaml          # Kubernetes namespace
│   ├── deployment.yaml         # Application deployment
│   ├── service.yaml            # ClusterIP service
│   └── ingress.yaml            # GKE Ingress (GCE)
├── scripts/
│   ├── build-push.sh           # Linux/macOS: build & push image
│   ├── build-push.bat          # Windows: build & push image
│   ├── deploy-image.sh         # Linux/macOS: deploy to GKE
│   └── deploy-image.bat        # Windows: deploy to GKE
└── docs/
    └── DEPLOYMENT.md           # This guide
```

---

## Local Development with Docker Compose

The `docker-compose.yml` runs **only the application container**. External services (Redis, Pub/Sub emulator) must be provided separately or mocked via environment variables.

### 1. Configure environment variables

Create a `.env` file in the project root:

```env
REDIS_HOST=your-redis-host
REDIS_PORT=6379
GCP_PROJECT_ID=your-gcp-project
PUBSUB_BOOKING_TOPIC=booking-events
PUBSUB_REPORT_TOPIC=report-events
JWT_SIGNING_KEY=your-secure-signing-key-min-32-chars
REPORT_BASE_PATH=/var/reports
BOOKING_CACHE_TTL_SECONDS=3600
PAYMENT_API_URL=http://payment-service:9090/payments/charge
```

### 2. Build and start

```bash
docker compose up --build
```

### 3. Verify the application

```bash
curl http://localhost:8080/actuator/health
```

Expected response:
```json
{"status":"UP"}
```

### 4. Stop the application

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

### Windows

```cmd
scripts\build-push.bat
```

Both scripts will prompt you to:
1. Select a registry (Google Artifact Registry or Docker Hub)
2. Enter registry credentials / GCP details
3. Enter an image tag (defaults to `latest`)

The scripts build from the repository root using:
```bash
docker build -f Dockerfile -t <registry>/<repo>/resortslite:<tag> .
```

---

## GCP GKE Deployment

### Step 1: Authenticate with GCP

```bash
gcloud auth login
gcloud config set project YOUR_GCP_PROJECT_ID
```

### Step 2: Configure Artifact Registry authentication

```bash
gcloud auth configure-docker REGION-docker.pkg.dev
```

### Step 3: Build and push the image

```bash
./scripts/build-push.sh
```

### Step 4: Create GCP Secret Manager secrets

Store the JWT signing key securely:

```bash
echo -n "your-secure-signing-key-min-32-chars" | \
  gcloud secrets create jwt-signing-key --data-file=-
```

### Step 5: Create Kubernetes Secret for JWT key

```bash
kubectl create namespace resortslite --dry-run=client -o yaml | kubectl apply -f -

kubectl create secret generic resortslite-secrets \
  --namespace=resortslite \
  --from-literal=jwt-signing-key="your-secure-signing-key-min-32-chars"
```

### Step 6: Deploy to GKE

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

The script will prompt for:
- GCP Project ID
- GCP Zone (e.g. `us-central1-a`)
- GKE Cluster Name
- Full Docker image URI
- Application environment variables (Redis host/port, Pub/Sub topics, etc.)

It will then:
1. Configure `kubectl` for your GKE cluster
2. Replace all `{{PLACEHOLDER}}` values in the Kubernetes manifests
3. Apply manifests in order: namespace → deployment → service → ingress
4. Wait for the rollout to complete
5. Display the ingress IP address

### Step 7: Verify the deployment

```bash
kubectl get pods,svc,ingress -n resortslite
kubectl logs -l app=resortslite -n resortslite --tail=50
curl http://<INGRESS_IP>/actuator/health
```

---

## Kubernetes Manifest Reference

### namespace.yaml
Creates the `resortslite` namespace to isolate all application resources.

### deployment.yaml
- **Replicas**: 2 (for high availability)
- **Image**: `{{IMAGE_URI}}` — replaced by `deploy-image.sh` at deploy time
- **Resources**:
  - Requests: `cpu: 250m`, `memory: 512Mi`
  - Limits: `cpu: 500m`, `memory: 1Gi`
- **Liveness probe**: `GET /actuator/health` — initial delay 60s, period 30s
- **Readiness probe**: `GET /actuator/health` — initial delay 30s, period 15s
- **Security context**: runs as non-root user (UID 1000)
- **JVM flags**: `-Xms256m -Xmx512m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`

### service.yaml
- **Type**: `ClusterIP` — internal cluster access only
- **Port mapping**: `80 → 8080`

### ingress.yaml
- **Class**: `gce` (GKE native HTTP(S) Load Balancer)
- **Host**: `resortslite.example.com` — update to your actual domain
- **Annotations**: `kubernetes.io/ingress.global-static-ip-name` for a static IP reservation

---

## Configuration and Environment Variables

| Variable | Default | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `REDIS_HOST` | `redis-service` | Redis hostname (in-cluster service name) |
| `REDIS_PORT` | `6379` | Redis port |
| `GCP_PROJECT_ID` | `my-gcp-project` | GCP project for Pub/Sub |
| `PUBSUB_BOOKING_TOPIC` | `booking-events` | Pub/Sub topic for booking events |
| `PUBSUB_REPORT_TOPIC` | `report-events` | Pub/Sub topic for report events |
| `JWT_SIGNING_KEY` | *(from Secret)* | HMAC-SHA256 signing key (min 32 chars) |
| `REPORT_BASE_PATH` | `/var/reports` | Base path for report files |
| `BOOKING_CACHE_TTL_SECONDS` | `3600` | Redis booking cache TTL in seconds |
| `PAYMENT_API_URL` | `http://payment-service:9090/payments/charge` | Payment service endpoint |

### Updating configuration without redeployment

```bash
kubectl set env deployment/resortslite -n resortslite BOOKING_CACHE_TTL_SECONDS=7200
```

---

## GKE Scaling and Management

### Manual scaling

```bash
kubectl scale deployment resortslite --replicas=4 -n resortslite
```

### Horizontal Pod Autoscaler (HPA)

```bash
kubectl autoscale deployment resortslite \
  --namespace=resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10
```

### Rolling update (new image)

```bash
kubectl set image deployment/resortslite \
  resortslite=REGION-docker.pkg.dev/PROJECT/REPO/resortslite:NEW_TAG \
  -n resortslite
kubectl rollout status deployment/resortslite -n resortslite
```

### Rollback

```bash
kubectl rollout undo deployment/resortslite -n resortslite
kubectl rollout status deployment/resortslite -n resortslite
```

### View rollout history

```bash
kubectl rollout history deployment/resortslite -n resortslite
```

---

## Troubleshooting

### Pods not starting

```bash
kubectl describe pod -l app=resortslite -n resortslite
kubectl logs -l app=resortslite -n resortslite --previous
```

**Common causes**:
- `ImagePullBackOff`: Check image URI and Artifact Registry permissions
- `CrashLoopBackOff`: Check application logs; likely a missing env var or Redis connection failure
- `Pending`: Insufficient cluster resources — scale the node pool

### Redis connection failures

```bash
kubectl exec -it $(kubectl get pod -l app=resortslite -n resortslite -o jsonpath='{.items[0].metadata.name}') \
  -n resortslite -- sh -c "nc -zv $REDIS_HOST $REDIS_PORT"
```

Verify `REDIS_HOST` and `REDIS_PORT` environment variables are set correctly.

### Pub/Sub authentication failures

Ensure the GKE node service account (or Workload Identity) has the `roles/pubsub.publisher` role:

```bash
gcloud projects add-iam-policy-binding YOUR_PROJECT \
  --member="serviceAccount:YOUR_SA@YOUR_PROJECT.iam.gserviceaccount.com" \
  --role="roles/pubsub.publisher"
```

### Ingress IP pending

GKE HTTP(S) Load Balancer provisioning can take 5–10 minutes. Monitor with:

```bash
kubectl get ingress resortslite-ingress -n resortslite -w
```

### Health check failures

```bash
kubectl exec -it <pod-name> -n resortslite -- \
  wget -qO- http://localhost:8080/actuator/health
```

### JVM out-of-memory errors

Increase the memory limit in `deployment.yaml` and adjust JVM flags:

```yaml
resources:
  limits:
    memory: "2Gi"
env:
  - name: JAVA_OPTS
    value: "-Xms512m -Xmx1536m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
```

---

## Security Considerations

1. **JWT Signing Key**: Always store in GCP Secret Manager and mount via Kubernetes Secret or Secret Store CSI driver. Never hardcode in source code or ConfigMaps.

2. **Non-root container**: The Dockerfile creates a dedicated `appuser` (UID 1000). The Kubernetes deployment enforces `runAsNonRoot: true`.

3. **Network policies**: Consider adding Kubernetes NetworkPolicy resources to restrict pod-to-pod communication to only required paths.

4. **Secrets rotation**: Rotate `JWT_SIGNING_KEY` periodically. Use GCP Secret Manager versioning and update the Kubernetes Secret accordingly.

5. **Image scanning**: Enable Artifact Registry vulnerability scanning on the repository to detect CVEs in the base image and dependencies.

6. **HTTPS**: Configure a Google-managed SSL certificate via `networking.gke.io/managed-certificates` annotation on the Ingress for production deployments.

7. **Workload Identity**: Bind the GKE pod service account to a GCP service account with least-privilege IAM roles for Pub/Sub and Secret Manager access.

---

## Java-Specific Notes

### JVM Container Awareness
The Dockerfile sets `-XX:+UseContainerSupport` and `-XX:MaxRAMPercentage=75.0` so the JVM respects the container memory limit rather than the host memory. This prevents OOMKilled events on GKE.

### Spring Boot Actuator
The `/actuator/health` endpoint is exposed via `management.endpoints.web.exposure.include=health` in `application.properties`. Both liveness and readiness probes use this endpoint. The initial delay for liveness is set to 60 seconds to allow for JVM warm-up.

### Spring Session Data Redis
Session data is stored in Redis under the `resortsLite:session` namespace. Ensure the Redis instance is available before the application starts. In GKE, deploy Redis as a StatefulSet with a PersistentVolumeClaim backed by a GCP Persistent Disk.

### Google Cloud Pub/Sub
The application uses Application Default Credentials (ADC) for Pub/Sub authentication. On GKE, configure Workload Identity to bind the pod's Kubernetes service account to a GCP service account with `roles/pubsub.publisher`.

### H2 Database
The application uses an H2 in-memory database for development. For production, replace with Cloud SQL (PostgreSQL or MySQL) and update `spring.datasource.*` properties accordingly.

### Maven Build
The Docker build uses the system `mvn` command (not the Maven wrapper) to avoid wrapper dependency issues. The `.dockerignore` file excludes `mvnw`, `mvnw.cmd`, and `.mvn/` from the build context.
