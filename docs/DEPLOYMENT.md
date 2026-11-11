# ResortsLite – Deployment Guide (Docker & Azure AKS)

## 1. Application Summary
- Spring Boot 2.7.18, Java 8, Maven, JAR packaging
- Port: 8080 (`server.port`), Actuator health on same port: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`
- External dependencies: SQL Server (JDBC), Azure Cache for Redis (Spring Session), payment API
- Runtime base image: `openjdk:8-jdk` (explicitly requested); builder: `maven:3.9.4-eclipse-temurin-8`

## 2. Prerequisites
- Docker 20.10+, Azure CLI 2.40+, kubectl, an Azure subscription
- An Azure Container Registry (or Docker Hub account) and an AKS cluster with the Application Gateway Ingress Controller (AGIC) enabled
- AKS must be able to pull from ACR: `az aks update -g <rg> -n <cluster> --attach-acr <acr>`

## 3. Local Development with Docker Compose
```bash
export SPRING_DATASOURCE_URL="jdbc:sqlserver://<host>:1433;databaseName=ResortsDB;encrypt=true"
export SPRING_DATASOURCE_USERNAME=<user>
export SPRING_DATASOURCE_PASSWORD=<password>
export REDIS_HOST=<redis-host> REDIS_PORT=6380 REDIS_PASSWORD=<key> REDIS_SSL_ENABLED=true
docker compose up --build
curl http://localhost:8080/actuator/health
```
Only the application service is defined; the database and Redis must be provided externally. Spring Session requires a reachable Redis.

## 4. Build and Push
From the repository root:
```bash
./scripts/build-push.sh        # Linux/macOS
scripts\build-push.bat         # Windows
```
Choose 1 (ACR – runs `az acr login`) or 2 (Docker Hub). Tags are lower-cased/sanitized; empty defaults to `latest`.

## 5. Deploy to AKS
```bash
./scripts/deploy-image.sh      # or scripts\deploy-image.bat
```
The script prompts for resource group, cluster, image URI, ingress host and app settings, then:
1. Copies manifests to a temp dir and replaces `{{IMAGE_URI}}`, `{{INGRESS_HOST}}`, `{{REDIS_HOST}}`, `{{REDIS_PORT}}`, `{{REDIS_SSL_ENABLED}}`, `{{PAYMENT_API_URL}}`
2. `az aks get-credentials` and `kubectl cluster-info`
3. Applies namespace, creates secret `resortslite-secrets` (DB URL/user/password, Redis password), then deployment, service, ingress
4. Waits for rollout and prints pods/services/ingress

### Manifests (`kubernetes/`)
| File | Purpose |
|---|---|
| namespace.yaml | `resortslite` namespace |
| deployment.yaml | 2 replicas, 250m/512Mi requests, 500m/1Gi limits, startup/liveness/readiness probes |
| service.yaml | ClusterIP, port 80 → 8080 |
| ingress.yaml | AGIC ingress (`azure/application-gateway`) |

## 6. Configuration
| Variable | Description | Default |
|---|---|---|
| SPRING_DATASOURCE_URL / USERNAME / PASSWORD | SQL Server connection (secret) | values in application.properties |
| REDIS_HOST / REDIS_PORT / REDIS_PASSWORD / REDIS_SSL_ENABLED | Spring Session Redis | localhost / 6380 / – / true |
| PAYMENT_API_URL | Payment endpoint | http://payment-svc.internal:9090/payments/charge |
| REPORT_BASE_PATH / BACKUP_PATH | Writable dirs | /app/reports/, /app/backups/ |
| JAVA_OPTS | JVM flags | `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0` |

## 7. Scaling and Management
```bash
kubectl scale deployment/resortslite -n resortslite --replicas=4
kubectl autoscale deployment resortslite -n resortslite --cpu-percent=70 --min=2 --max=6
kubectl set image deployment/resortslite resortslite=<new-image> -n resortslite
kubectl rollout status deployment/resortslite -n resortslite
kubectl rollout undo deployment/resortslite -n resortslite
```

## 8. Troubleshooting
- **ImagePullBackOff**: attach ACR to AKS, verify the image URI/tag.
- **CrashLoopBackOff / probe failures**: `kubectl logs -l app=resortslite -n resortslite`. Usually DB or Redis unreachable (Spring Session needs Redis at startup) or wrong credentials. The startup probe allows ~5 min.
- **OOMKilled**: raise memory limit or lower `MaxRAMPercentage`.
- **Service issues**: `kubectl get endpoints -n resortslite`; endpoints empty means pods not Ready.
- **Ingress 502/404**: check AGIC pod logs (`kubectl logs -n kube-system -l app=ingress-appgw`), host header, and Application Gateway backend health.

## 9. Security Notes
- Container runs as non-root (`appuser`). No wrapper scripts or package installs in the image.
- Credentials are passed via Kubernetes Secret; consider Azure Key Vault Secrets Store CSI driver. `application.properties` currently contains hardcoded DB credentials – override via env vars and remove them from source.
- `log4j-core 2.14.1` (CVE-2021-44228) and `commons-collections 3.2.1` are vulnerable – upgrade before production.
- `openjdk:8-jdk` is deprecated upstream and no longer receives updates; plan a move to `eclipse-temurin:8-jre-alpine` or newer LTS.
- Terminate TLS at the Application Gateway; the app also calls plain-HTTP internal services which should be moved to HTTPS.

## 10. Java Notes
- Java 8u191+ supports container-aware memory; `MaxRAMPercentage` is used instead of fixed heap.
- Graceful shutdown: the JVM runs via `exec` and receives SIGTERM.
- Reports/backups use `emptyDir` volumes; use a PVC or Azure Files if they must persist.
