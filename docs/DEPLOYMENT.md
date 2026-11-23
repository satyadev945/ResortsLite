# ResortsLite - Deployment Guide (AWS EKS)

## Application summary
- Spring Boot 2.7.18, Java 8, Maven, executable JAR (`target/resortsLite-1.0.0.jar`)
- Port: `8080` (`server.port`); health: `/actuator/health` (liveness `/actuator/health/liveness`, readiness `/actuator/health/readiness`)
- External dependencies: SQL Server (JDBC), Amazon ElastiCache Redis (Spring Session + booking cache), payment service

## Prerequisites
Docker 20+, AWS CLI v2 (configured), kubectl, eksctl (optional), an EKS cluster with the AWS Load Balancer Controller installed, and IAM permissions for ECR and EKS.

## Local development (Docker Compose)
```bash
export REDIS_HOST=host.docker.internal
docker compose up --build
curl http://localhost:8080/actuator/health
```
Defaults use in-memory H2. Override `SPRING_DATASOURCE_*` for SQL Server (also set `SPRING_DATASOURCE_DRIVER_CLASS_NAME=com.microsoft.sqlserver.jdbc.SQLServerDriver` and `SPRING_JPA_DATABASE_PLATFORM=org.hibernate.dialect.SQLServer2012Dialect`).

## Build and push
Run from repo root: `./scripts/build-push.sh` (or `scripts\build-push.bat`). Choose ECR or Docker Hub. The script sanitises the tag, creates the ECR repo if missing, builds and pushes. Note the printed image URI.

## Deploy to EKS
```bash
./scripts/deploy-image.sh     # or scripts\deploy-image.bat
```
The script prompts for region, cluster, image URI and app settings, replaces `{{PLACEHOLDER}}` values in a temporary copy of `kubernetes/*.yaml`, configures kubectl, creates the `resortslite-secrets` Secret (DB and Redis passwords), applies namespace → deployment → service → ingress, and waits for rollout.

Manifests:
- `namespace.yaml` – namespace `resortslite`
- `deployment.yaml` – 2 replicas, requests 250m/512Mi, limits 500m/1Gi, probes, emptyDir for `/app/reports` and `/app/backups`
- `service.yaml` – ClusterIP 80 → 8080
- `ingress.yaml` – ALB (internet-facing, target-type ip); change host `resortslite.example.com`

## Configuration
The application.properties hardcodes the datasource and internal endpoints; these are overridden at runtime through Spring relaxed binding environment variables (`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`), plus `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_SSL_ENABLED`, `PAYMENT_API_URL`, `REPORT_BASE_PATH`, `BACKUP_PATH`, `JAVA_OPTS`.
Reports/backups use emptyDir (ephemeral); use a PVC or S3 for persistence.

## Operations
- Scale: `kubectl scale deployment/resortslite -n resortslite --replicas=3`; HPA: `kubectl autoscale deployment resortslite -n resortslite --cpu-percent=70 --min=2 --max=6`
- Rolling update: re-run deploy script with new tag; rollback: `kubectl rollout undo deployment/resortslite -n resortslite`

## Troubleshooting
- Pods CrashLoopBackOff: `kubectl logs -n resortslite deploy/resortslite`; check DB/Redis connectivity and security groups (EKS nodes → RDS/ElastiCache).
- Readiness failing: check `/actuator/health`; verify Redis host and SSL setting.
- ImagePullBackOff: verify image URI and node role has ECR read access.
- Ingress has no address: `kubectl describe ingress -n resortslite`; confirm AWS Load Balancer Controller is running and subnets are tagged.
- Service issues: `kubectl get endpoints -n resortslite`.

## Security
Container runs as non-root; passwords go through a Kubernetes Secret (consider AWS Secrets Manager + External Secrets). Dependencies `log4j-core 2.14.1` (Log4Shell) and `commons-collections 3.2.1` are vulnerable and should be upgraded before production. Use HTTPS on the ALB with an ACM certificate.

## Java notes
Runtime image `eclipse-temurin:8-jdk-alpine`; JVM uses `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`; TZ=UTC.
