# ResortsLite – Deployment Guide (Docker & AWS EKS)

## 1. Application Summary
- Spring Boot 2.7.18, Java 8, Maven, packaged as an executable JAR
- Port: 8080 (`SERVER_PORT`), health: `/actuator/health` (liveness `/actuator/health/liveness`, readiness `/actuator/health/readiness`)
- External dependencies: SQL Server (JDBC), Redis / Amazon ElastiCache (Spring Session + booking cache), payment service (`PAYMENT_API_URL`)
- Runtime image: `eclipse-temurin:8-jdk-alpine` (explicit base image), builder `maven:3.9.4-eclipse-temurin-8`

## 2. Prerequisites
Docker 20+, AWS CLI v2, kubectl, (optional) eksctl, an ECR or Docker Hub account, an EKS cluster with the AWS Load Balancer Controller installed, IAM permissions for ECR, `eks:DescribeCluster`, and cluster access (aws-auth / access entries).

## 3. Local Development with Docker Compose
Only the application container is defined; SQL Server and Redis must be reachable externally.

```bash
export SPRING_DATASOURCE_URL='jdbc:sqlserver://<host>:1433;databaseName=ResortsDB;encrypt=true;trustServerCertificate=true'
export SPRING_DATASOURCE_USERNAME=<user>
export SPRING_DATASOURCE_PASSWORD=<password>
export REDIS_HOST=<redis-host>
docker compose up --build
curl http://localhost:8080/actuator/health
```

## 4. Build and Push
From the repository root:
```bash
./scripts/build-push.sh      # Linux/macOS
scripts\build-push.bat       # Windows
```
Choose 1 (AWS ECR – repository is auto-created) or 2 (Docker Hub). Tags are lower-cased/sanitized; empty tag becomes `latest`. Note the printed image URI.

## 5. Deploy to EKS
```bash
./scripts/deploy-image.sh    # or scripts\deploy-image.bat
```
The script prompts for region, cluster, image URI and app configuration, substitutes `{{PLACEHOLDERS}}` in a temp copy of `kubernetes/`, runs `aws eks update-kubeconfig`, applies manifests in order (namespace, secret, deployment, service, ingress), waits for rollout and prints resources.

Manifests:
| File | Purpose |
|---|---|
| namespace.yaml | `resortslite` namespace |
| secret.yaml | DB URL/credentials and Redis password (Opaque Secret) |
| deployment.yaml | 2 replicas, probes, resources 250m/512Mi – 500m/1Gi, emptyDir for reports/backups |
| service.yaml | ClusterIP 80 → 8080 |
| ingress.yaml | ALB (internet-facing, ip target type); change host `resortslite.example.com` |

## 6. Configuration
`application.properties` hardcodes the datasource; the container overrides it through Spring relaxed binding with `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`. Other variables: `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_SSL_ENABLED` (true for ElastiCache in-transit encryption), `SESSION_TIMEOUT`, `BOOKING_CACHE_PREFIX`, `BOOKING_CACHE_TTL_SECONDS`, `PAYMENT_API_URL`, `REPORT_BASE_PATH`, `BACKUP_BASE_PATH`, `JAVA_OPTS`.
Note: `app.inventory.endpoint`/`app.notification.endpoint` in properties are hardcoded internal hosts; override via `APP_INVENTORY_ENDPOINT` / `APP_NOTIFICATION_ENDPOINT` if used.

## 7. Operations
- Scale: `kubectl scale deployment/resortslite -n resortslite --replicas=4`; HPA: `kubectl autoscale deployment resortslite -n resortslite --cpu-percent=70 --min=2 --max=6`
- Rolling update: re-run deploy script with a new image tag; rollback: `kubectl rollout undo deployment/resortslite -n resortslite`
- Logs: `kubectl logs -l app=resortslite -n resortslite`

## 8. Troubleshooting
- Pods `CrashLoopBackOff`: check logs; typically DB URL/credentials wrong or Redis unreachable (security groups from EKS nodes to RDS/SQL Server and ElastiCache on 1433/6379).
- `ImagePullBackOff`: ECR permissions on node role or wrong image URI.
- Probes failing: JVM start takes 30–60s; raise `initialDelaySeconds`.
- Ingress has no address: confirm AWS Load Balancer Controller is installed and subnets are tagged; `kubectl describe ingress -n resortslite`.
- Service check: `kubectl port-forward svc/resortslite-service 8080:80 -n resortslite`.

## 9. Security
- Container runs as non-root user `spring`; image has no extra tooling.
- Credentials live in a Kubernetes Secret; prefer AWS Secrets Manager + External Secrets for production, and remove the hardcoded password from `application.properties`.
- Known vulnerable dependencies in pom.xml (log4j-core 2.14.1, commons-collections 3.2.1) should be upgraded before production.
- Use HTTPS on the ALB (ACM certificate annotation) and TLS (`encrypt=true`) to SQL Server.

## 10. Java Notes
Java 8u191+ is container-aware: heap is set with `-XX:MaxRAMPercentage=75.0`. Timezone `TZ=UTC`. Spring Session uses Redis so replicas share sessions.
