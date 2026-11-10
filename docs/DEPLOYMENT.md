# ResortsLite – Deployment Guide (Azure AKS)

## Application summary
- Java 8, Spring Boot 2.7.18, Maven, executable JAR (`resortsLite-1.0.0.jar`)
- Port: `8080` (`SERVER_PORT`); health: `/actuator/health` (probes: `/actuator/health/liveness`, `/actuator/health/readiness`)
- External dependencies: SQL Server (JDBC), Azure Cache for Redis (Spring Session), payment service
- Runtime image: `amazoncorretto:8` (explicit); builder: `maven:3.9.4-eclipse-temurin-8`

## Prerequisites
Docker, Azure CLI (`az login`), kubectl, an Azure subscription, an ACR (or Docker Hub account), an AKS cluster with the Application Gateway Ingress Controller (AGIC) add-on, and AKS permission to pull from ACR (`az aks update -n <aks> -g <rg> --attach-acr <acr>`).

## Local development (Docker Compose)
```
export SPRING_DATASOURCE_URL='jdbc:sqlserver://<host>:1433;databaseName=ResortsDB;encrypt=false;trustServerCertificate=true'
export SPRING_DATASOURCE_USERNAME=<user>
export SPRING_DATASOURCE_PASSWORD=<password>
export REDIS_HOST=<redis-host> REDIS_PORT=6379 REDIS_SSL_ENABLED=false
docker compose up --build
curl http://localhost:8080/actuator/health
```
Only the application is defined; SQL Server and Redis must be provided externally.
Spring relaxed binding means `SPRING_DATASOURCE_*` env vars override the values hardcoded in `application.properties`.

## Build and push
Run from the repository root:
- Linux/macOS: `./scripts/build-push.sh`
- Windows: `scripts\build-push.bat`

The script prompts for tag (default `latest`) and registry (1. ACR, 2. Docker Hub), builds with `docker build -f Dockerfile -t <image> .` and pushes.

## Deploy to AKS
- Linux/macOS: `./scripts/deploy-image.sh`
- Windows: `scripts\deploy-image.bat`

The script prompts for resource group, cluster name, image URI and app config. It renders the manifests in a temp directory (placeholders `{{IMAGE_URI}}`, `{{SPRING_DATASOURCE_URL}}`, `{{REDIS_HOST}}`, `{{REDIS_PORT}}`, `{{REDIS_SSL_ENABLED}}`, `{{BOOKING_CACHE_TTL_SECONDS}}`, `{{PAYMENT_API_URL}}`), runs `az aks get-credentials`, creates the `resortslite-secrets` Secret (DB user/password, Redis password), and applies namespace → deployment → service → ingress, then waits for rollout.

### Manifests
| File | Purpose |
|---|---|
| `kubernetes/namespace.yaml` | `resortslite` namespace |
| `kubernetes/deployment.yaml` | 2 replicas, probes, resources (250m/512Mi req, 500m/1Gi limit), non-root, emptyDir for reports/backups |
| `kubernetes/service.yaml` | ClusterIP 80 → 8080 |
| `kubernetes/ingress.yaml` | AGIC ingress, host `resortslite.example.com` (change to your domain) |

## Configuration
| Variable | Purpose | Default |
|---|---|---|
| `SERVER_PORT` | HTTP port | 8080 |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | SQL Server connection | from application.properties |
| `REDIS_HOST/PORT/PASSWORD/REDIS_SSL_ENABLED` | Spring Session store | localhost / 6380 / – / true |
| `BOOKING_CACHE_TTL_SECONDS` | booking cache TTL | 3600 |
| `PAYMENT_API_URL` | payment endpoint | http://payment-svc.internal:9090/payments/charge |
| `REPORT_BASE_PATH`, `BACKUP_BASE_PATH` | file paths | /app/reports/, /app/backups/ |
| `JAVA_OPTS` | JVM flags | MaxRAMPercentage=75, Xms256m |

For production, move secrets to Azure Key Vault (Secrets Store CSI Driver) and use a persistent volume instead of emptyDir for reports/backups.

## Operations
- Scale: `kubectl scale deployment/resortslite -n resortslite --replicas=4` or create an HPA (`kubectl autoscale deployment resortslite -n resortslite --cpu-percent=70 --min=2 --max=6`).
- Update: re-run deploy script with a new tag (rolling update, maxUnavailable 0).
- Rollback: `kubectl rollout undo deployment/resortslite -n resortslite`.

## Troubleshooting
- `ImagePullBackOff`: attach ACR to AKS, verify image URI/tag.
- `CrashLoopBackOff`: `kubectl logs -l app=resortslite -n resortslite`; check DB/Redis connectivity. The app uses JPA `ddl-auto=update` and connects to SQL Server at startup; readiness fails if Redis/DB is unreachable.
- Pod not Ready: `kubectl describe pod`; probe paths are `/actuator/health/liveness|readiness`.
- Service: `kubectl get endpoints -n resortslite`.
- Ingress has no address: confirm AGIC add-on is enabled (`kubectl get pods -n kube-system | grep ingress`), check `kubectl describe ingress`.

## Security notes
- Container runs as non-root UID 1001; no package managers/tools added to runtime.
- Known vulnerable dependencies in `pom.xml` (log4j-core 2.14.1, commons-collections 3.2.1) and hardcoded credentials in `application.properties` should be remediated before production.
- `trustServerCertificate=true`/`encrypt=false` should be removed for production SQL connections.
