# ResortsLite - Deployment Guide (AWS EKS)

## 1. Overview
ResortsLite is a Spring Boot 2.7.18 / Java 8 web application (Maven, executable JAR) listening on port **8080**. It uses SQL Server (JDBC/JPA), Redis (Spring Session + cache, Amazon ElastiCache) and exposes Actuator health at `/actuator/health` (probes: `/actuator/health/liveness`, `/actuator/health/readiness`).

Runtime image: `eclipse-temurin:8-jdk-alpine` (explicit base image). Builder: `maven:3.9.4-eclipse-temurin-8`.

## 2. Prerequisites
- Docker 20.10+
- AWS CLI v2 configured (`aws configure`) with permissions for ECR and EKS
- kubectl matching your cluster version, eksctl (optional)
- An EKS cluster with the **AWS Load Balancer Controller** installed (required by the ALB Ingress)
- A reachable SQL Server database and a Redis/ElastiCache endpoint from the cluster VPC

## 3. Local development with Docker Compose
Only the application container is defined. Supply external services via environment variables:
```bash
export SPRING_DATASOURCE_URL='jdbc:sqlserver://<host>:1433;databaseName=ResortsDB;encrypt=false;trustServerCertificate=true'
export SPRING_DATASOURCE_USERNAME=<user>
export SPRING_DATASOURCE_PASSWORD=<password>
export REDIS_HOST=<redis-host>
docker compose up --build
```
Open http://localhost:8080/actuator/health.

## 4. Build and push the image
From the project root:
```bash
./scripts/build-push.sh        # Linux/macOS
scripts\build-push.bat         # Windows
```
Choose 1 (AWS ECR) or 2 (Docker Hub). The script sanitizes the image name/tag (lowercase, hyphenated, default `latest`), creates the ECR repository if needed, builds and pushes. Note the printed image URI.

## 5. Deploy to EKS
```bash
./scripts/deploy-image.sh      # Linux/macOS
scripts\deploy-image.bat       # Windows
```
The script prompts for region, cluster, image URI, DB/Redis settings and ingress host; renders manifests in a temp directory (templates in `kubernetes/` keep their `{{PLACEHOLDERS}}`); runs `aws eks update-kubeconfig`; applies namespace, deployment, service, ingress; waits for rollout and prints resources.

### Manifests
| File | Purpose |
|------|---------|
| `kubernetes/namespace.yaml` | `resortslite` namespace |
| `kubernetes/deployment.yaml` | 2 replicas, probes, resources 250m/512Mi - 500m/1Gi |
| `kubernetes/service.yaml` | ClusterIP, port 80 -> 8080 |
| `kubernetes/ingress.yaml` | ALB (internet-facing, target-type ip) |

## 6. Configuration
`application.properties` hardcodes the datasource URL, username, password and some service endpoints. The container overrides the datasource via Spring relaxed binding:

| Variable | Description |
|----------|-------------|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | SQL Server connection (required) |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_SSL_ENABLED` | Redis / ElastiCache for sessions |
| `SESSION_TIMEOUT`, `SESSION_REDIS_NAMESPACE` | Session settings |
| `BOOKING_CACHE_TTL_SECONDS` | Booking cache TTL |
| `REPORT_BASE_PATH`, `REPORT_BACKUP_PATH` | Report directories (emptyDir mounted at `/app/reports`, `/app/backups`) |
| `SERVER_PORT` | HTTP port (default 8080) |
| `JAVA_OPTS` | JVM flags (MaxRAMPercentage=75) |

The `app.payment.endpoint`, `app.inventory.endpoint`, `app.notification.endpoint` properties are also hardcoded; externalize them (e.g. `APP_PAYMENT_ENDPOINT` env vars) before production use.

## 7. Scaling and management
```bash
kubectl scale deployment/resortslite -n resortslite --replicas=4
kubectl autoscale deployment resortslite -n resortslite --cpu-percent=70 --min=2 --max=6   # needs metrics-server
kubectl set image deployment/resortslite resortslite=<new-image> -n resortslite
kubectl rollout status deployment/resortslite -n resortslite
kubectl rollout undo deployment/resortslite -n resortslite
```
Reports are stored in emptyDir volumes (ephemeral); use an EFS PVC for persistence.

## 8. Troubleshooting
- **Pods CrashLoopBackOff**: `kubectl logs deploy/resortslite -n resortslite`; usually DB URL/credentials wrong or SQL Server unreachable (check security groups).
- **ImagePullBackOff**: node role needs ECR read permissions; verify image URI.
- **Readiness failing**: Redis unreachable (`REDIS_HOST`, security group, `REDIS_SSL_ENABLED` for in-transit encryption).
- **Ingress has no address**: confirm AWS Load Balancer Controller is running (`kubectl get pods -n kube-system`) and subnets are tagged; `kubectl describe ingress -n resortslite`.
- **503 from ALB**: check target health in the EC2 console; health path `/actuator/health/liveness`.

## 9. Security considerations
- Container runs as non-root user `spring`.
- Pass DB/Redis secrets with Kubernetes Secrets, AWS Secrets Manager (External Secrets / CSI driver) instead of plain env values; rotate the password that is currently committed in `application.properties`.
- Terminate TLS at the ALB (ACM certificate + `listen-ports` HTTPS annotation).
- Known vulnerable dependencies in `pom.xml`: log4j-core 2.14.1 (Log4Shell) and commons-collections 3.2.1 - upgrade (log4j >= 2.17.1).
- Java 8 / Spring Boot 2.7 are end-of-life; plan an upgrade.

## 10. Java notes
- The JVM uses container-aware memory (`-XX:MaxRAMPercentage=75.0`); keep the limit at 1Gi or adjust.
- `spring.jpa.hibernate.ddl-auto=update` modifies schema at startup; use migrations in production.
- Graceful shutdown: the entrypoint uses `exec` so SIGTERM reaches the JVM.
