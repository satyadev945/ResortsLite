# ResortsLite - Deployment Guide (AWS EKS)

## Application summary
- Spring Boot 2.7.18, Java 8, Maven, executable JAR (`target/resortsLite-1.0.0.jar`)
- Port: 8080 (`SERVER_PORT`), health: `/actuator/health` (liveness `/actuator/health/liveness`, readiness `/actuator/health/readiness`)
- External dependencies: SQL Server (JDBC), Redis/ElastiCache (Spring Session), payment service
- Runtime base image: `eclipse-temurin:8-jre` (explicit); builder `maven:3.9.4-eclipse-temurin-8`

## Prerequisites
Docker 20+, AWS CLI v2 (configured), kubectl, eksctl (optional), an EKS cluster with the AWS Load Balancer Controller installed, IAM permissions for ECR and `eks:DescribeCluster`.

## Local development (Docker Compose)
```bash
export SPRING_DATASOURCE_URL='jdbc:sqlserver://<host>:1433;databaseName=ResortsDB;encrypt=false;trustServerCertificate=true'
export SPRING_DATASOURCE_USERNAME=<user> SPRING_DATASOURCE_PASSWORD=<pass>
export REDIS_HOST=<redis-host>
docker compose up --build
curl http://localhost:8080/actuator/health
```
Only the application service is defined; SQL Server and Redis must be provided separately.

## Build and push
```bash
./scripts/build-push.sh        # Linux/macOS
scripts\build-push.bat         # Windows
```
Choose AWS ECR (repository is auto-created) or Docker Hub. Tags are lowercased/sanitized; default `latest`. Note the resulting image URI.

## EKS setup
```bash
eksctl create cluster --name resortslite --region us-east-1 --nodes 2
aws eks update-kubeconfig --region us-east-1 --name resortslite
kubectl cluster-info
```
Install the AWS Load Balancer Controller for the ALB ingress.

## Deploy
```bash
./scripts/deploy-image.sh      # or scripts\deploy-image.bat
```
The script prompts for region, cluster, image URI and application settings, substitutes `{{PLACEHOLDERS}}` (in a temp copy, using `|` as sed delimiter), creates the `resortslite-secrets` Secret (DB user/password, Redis password), and applies namespace -> deployment -> service -> ingress, then waits for the rollout.

Manifests (`kubernetes/`): `namespace.yaml` (resortslite), `deployment.yaml` (2 replicas, 250m/512Mi requests, 500m/1Gi limits, startup/liveness/readiness probes, emptyDir for reports/backups), `service.yaml` (ClusterIP 80 -> 8080), `ingress.yaml` (ALB, internet-facing, host `resortslite.example.com` - change to your domain).

## Configuration
| Variable | Purpose |
|---|---|
| SPRING_DATASOURCE_URL / _USERNAME / _PASSWORD | SQL Server connection (overrides hardcoded values in application.properties) |
| REDIS_HOST, REDIS_PORT, REDIS_PASSWORD, REDIS_SSL_ENABLED | Spring Session store (ElastiCache) |
| PAYMENT_API_URL | Payment service endpoint |
| REPORT_BASE_PATH, BACKUP_PATH | File paths (emptyDir by default; use a PVC/EFS for persistence) |
| JAVA_OPTS | JVM options (`MaxRAMPercentage=75`) |

## Troubleshooting
- Pods `CrashLoopBackOff`: `kubectl logs -n resortslite deploy/resortslite`; check DB/Redis connectivity and credentials.
- Not ready: `kubectl describe pod -n resortslite <pod>`; startup probe allows ~5 minutes.
- `ImagePullBackOff`: verify the URI and that node IAM role has ECR read access.
- Ingress has no address: `kubectl describe ingress -n resortslite`; confirm AWS Load Balancer Controller is running and subnets are tagged.
- Service check: `kubectl port-forward -n resortslite svc/resortslite-service 8080:80`.

## Scaling and operations
```bash
kubectl scale deployment/resortslite -n resortslite --replicas=4
kubectl autoscale deployment resortslite -n resortslite --cpu-percent=70 --min=2 --max=6
kubectl rollout history deployment/resortslite -n resortslite
kubectl rollout undo deployment/resortslite -n resortslite
```
Rolling updates use maxUnavailable=0 / maxSurge=1.

## Security
- Container runs as non-root (uid 1001); no wrapper scripts or extra packages in the image.
- The repository's `application.properties` contains hardcoded DB credentials and known-vulnerable dependencies (log4j-core 2.14.1, commons-collections 3.2.1). Override credentials via Secrets (done here), move to AWS Secrets Manager, and upgrade those libraries.
- Use TLS on the ALB (ACM certificate annotations) and restrict security groups.

## Java notes
Session state is in Redis, so replicas are stateless. JVM is container-aware; adjust memory limits together with `MaxRAMPercentage`. Only the `health` actuator endpoint is exposed.
