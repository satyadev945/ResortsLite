# ResortsLite – Deployment Guide (Docker & Azure AKS)

## 1. Overview
ResortsLite is a Spring Boot 2.7.18 / Java 8 Maven application (JAR). It listens on port **8080** and exposes Actuator health at `/actuator/health` (Kubernetes probes use `/actuator/health/liveness` and `/actuator/health/readiness`). External dependencies (not containerized here): SQL Server and Azure Cache for Redis (Spring Session).

Runtime image: `eclipse-temurin:8-jre` (explicit base image). Builder: `maven:3.9.4-eclipse-temurin-8`.

## 2. Prerequisites
- Docker 20.10+ (and Docker Compose)
- Azure CLI (`az`) logged in: `az login`
- kubectl
- Azure subscription, an ACR (or Docker Hub account) and an AKS cluster with the Application Gateway Ingress Controller (AGIC) add-on
- AKS able to pull from ACR: `az aks update -g <rg> -n <cluster> --attach-acr <acr>`

## 3. Local development with Docker Compose
```bash
export SPRING_DATASOURCE_URL="jdbc:sqlserver://<host>:1433;databaseName=ResortsDB;encrypt=true;trustServerCertificate=true"
export SPRING_DATASOURCE_USERNAME=<user>
export SPRING_DATASOURCE_PASSWORD=<password>
export REDIS_HOST=host.docker.internal REDIS_PORT=6379 REDIS_SSL=false
docker compose up --build
```
App: http://localhost:8080, health: http://localhost:8080/actuator/health. Optional `./config` directory is mounted at `/app/config` (Spring Boot loads `application.properties` from there).

## 4. Build and push the image
Run from the repository root:
```bash
./scripts/build-push.sh        # Linux/macOS
scripts\build-push.bat         # Windows
```
Choose 1 (ACR – runs `az acr login`) or 2 (Docker Hub). Tags are lowercased/sanitized; empty tag defaults to `latest`. The resulting URI (e.g. `myacr.azurecr.io/resortslite:latest`) is printed.

## 5. Deploy to AKS
```bash
./scripts/deploy-image.sh      # Linux/macOS
scripts\deploy-image.bat       # Windows (uses scripts\render-manifests.ps1)
```
The script prompts for resource group, cluster name, image URI, DB URL/credentials, Redis settings, external endpoints and ingress host. It renders `{{PLACEHOLDER}}` values into a temp copy of the manifests (templates stay untouched), runs `az aks get-credentials`, verifies connectivity, then applies in order: namespace → deployment (includes Secret) → service → ingress, and waits for the rollout.

### Manifests (`kubernetes/`)
| File | Purpose |
|---|---|
| namespace.yaml | `resortslite` namespace |
| deployment.yaml | Secret `resortslite-secrets` (DB user/password, Redis password) + Deployment (2 replicas, 250m/512Mi requests, 500m/1Gi limits, startup/liveness/readiness probes) |
| service.yaml | ClusterIP, port 80 → 8080 |
| ingress.yaml | AGIC ingress (`azure/application-gateway`), host `{{INGRESS_HOST}}` |

## 6. Configuration
`application.properties` hardcodes DB credentials; the container overrides them through Spring relaxed binding environment variables (no code change needed):

| Variable | Purpose |
|---|---|
| SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD | SQL Server connection (required) |
| REDIS_HOST, REDIS_PORT, REDIS_PASSWORD, REDIS_SSL | Spring Session Redis |
| SESSION_REDIS_NAMESPACE, SESSION_TIMEOUT | Session settings |
| APP_PAYMENT_ENDPOINT, APP_INVENTORY_ENDPOINT, APP_NOTIFICATION_ENDPOINT | Downstream services |
| SERVER_PORT | HTTP port (default 8080) |
| JAVA_OPTS | JVM flags (`-XX:MaxRAMPercentage=75.0` etc.) |
| MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED | Enables `/actuator/health/liveness` and `/readiness` |

For production prefer Azure Key Vault with the Secrets Store CSI Driver rather than prompting for secrets.

## 7. Operations
```bash
kubectl get pods,svc,ingress -n resortslite
kubectl logs -l app=resortslite -n resortslite --tail=100
kubectl scale deployment/resortslite -n resortslite --replicas=3
kubectl autoscale deployment resortslite -n resortslite --cpu-percent=70 --min=2 --max=6
kubectl set image deployment/resortslite resortslite=<new-image> -n resortslite   # rolling update
kubectl rollout undo deployment/resortslite -n resortslite                         # rollback
```

## 8. Troubleshooting
- **ImagePullBackOff**: attach ACR to AKS (`--attach-acr`) or verify the URI/tag.
- **CrashLoopBackOff**: check logs; usually DB unreachable (JPA `ddl-auto=update` connects at startup) – verify URL, credentials, firewall/VNet rules.
- **Not ready**: readiness is `/actuator/health/readiness`; check `kubectl describe pod`.
- **OOMKilled**: raise memory limit; heap is 75% of the container limit.
- **Ingress has no IP / 502**: confirm AGIC add-on (`az aks show ... --query addonProfiles`), check `kubectl describe ingress -n resortslite`, and that pods are Ready.
- **Sessions lost / Redis errors**: verify Redis host, port 6380 with SSL for Azure Cache for Redis, and the access key.

## 9. Security notes
- Container runs as non-root (uid 1001); no package managers or extra tools in the runtime image.
- Secrets are stored in a Kubernetes Secret rendered at deploy time; never commit rendered manifests.
- The pom pins vulnerable `log4j-core 2.14.1` (Log4Shell) and `commons-collections 3.2.1`; upgrade them before production use.
- Java 8 is end-of-life for many distributions; plan a move to Java 17+.
- Rotate the previously hardcoded DB password present in `application.properties`.

## 10. Java notes
- Java 8u191+ is container-aware; `UseContainerSupport` and `MaxRAMPercentage` are set.
- `exec java` entrypoint ensures SIGTERM reaches the JVM for graceful shutdown (30s grace period).
- Startup probe allows up to 5 minutes for slow JVM/DB initialization.
