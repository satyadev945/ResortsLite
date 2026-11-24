# ResortsLite – Deployment Guide (Docker & AWS EKS)

## 1. Overview
- Spring Boot 2.7.18, Java 8, Maven, executable JAR
- Port 8080; health: `/actuator/health` (liveness `/actuator/health/liveness`, readiness `/actuator/health/readiness`)
- External dependencies (not containerised here): Oracle DB, Redis/ElastiCache (sessions + booking cache), payment/inventory/notification services
- Runtime base image: `eclipse-temurin:8-jdk-alpine` (explicitly requested)

## 2. Prerequisites
Docker 20+, AWS CLI v2, kubectl, eksctl (optional), IAM permissions for ECR and EKS, an existing EKS cluster with the AWS Load Balancer Controller installed.

## 3. Local Development
```
export SPRING_DATASOURCE_URL=jdbc:oracle:thin:@host:1521:ORCL
export SPRING_DATASOURCE_USERNAME=... SPRING_DATASOURCE_PASSWORD=...
export REDIS_HOST=... 
docker compose up --build
curl http://localhost:8080/actuator/health
```
Only the application service is defined; Oracle and Redis must be reachable externally.

## 4. Build and Push
Run from repository root:
```
./scripts/build-push.sh      # Linux/macOS
scripts\build-push.bat       # Windows
```
Choose AWS ECR (repository auto-created) or Docker Hub. Tags are sanitised (lowercase, default `latest`).

## 5. EKS Deployment
```
./scripts/deploy-image.sh    # or scripts\deploy-image.bat
```
The script prompts for region, cluster, image URI and app settings, substitutes `{{PLACEHOLDER}}` values into a temporary copy of `kubernetes/deployment.yaml` (originals untouched), runs `aws eks update-kubeconfig`, applies namespace → deployment → service → ingress, waits for rollout and prints the ingress address.

Manifests:
- `namespace.yaml` – namespace `resortslite`
- `deployment.yaml` – 2 replicas, requests 250m/512Mi, limits 500m/1Gi, startup/liveness/readiness probes
- `service.yaml` – ClusterIP 80 → 8080
- `ingress.yaml` – ALB internet-facing; change host `resortslite.example.com`

## 6. Configuration
Spring relaxed binding maps env vars (`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `APP_PAYMENT_ENDPOINT`, ...) over the hardcoded values in `application.properties`. Redis: `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_SSL_ENABLED`. Other: `BOOKING_CACHE_TTL_SECONDS`, `SESSION_TIMEOUT`, `PAYMENT_API_URL`, `REPORT_BASE_PATH`, `BACKUP_PATH`, `JAVA_OPTS`.

## 7. Scaling & Operations
```
kubectl scale deployment/resortslite -n resortslite --replicas=4
kubectl autoscale deployment resortslite -n resortslite --cpu-percent=70 --min=2 --max=6
kubectl set image deployment/resortslite resortslite=<new-image> -n resortslite
kubectl rollout undo deployment/resortslite -n resortslite
```
Sessions are stored in Redis so replicas can scale freely. Reports/backups use emptyDir; use a PVC or S3 for persistence.

## 8. Troubleshooting
- Pods CrashLoopBackOff: `kubectl logs <pod> -n resortslite` – usually Oracle URL/credentials or Redis unreachable (check security groups).
- ImagePullBackOff: verify node role has ECR read access and image URI is correct.
- Probes failing: Oracle/Redis health contributes to `/actuator/health`; readiness requires dependencies reachable.
- Ingress has no address: check the AWS Load Balancer Controller: `kubectl logs -n kube-system deploy/aws-load-balancer-controller`.
- `kubectl describe pod`, `kubectl get events -n resortslite`.

## 9. Security
- Container runs as non-root user `appuser`.
- Do not keep DB passwords in manifests for production: use AWS Secrets Manager / External Secrets or Kubernetes Secrets, and remove hardcoded credentials from `application.properties`.
- Known vulnerable dependencies in pom.xml (log4j-core 2.14.1, commons-collections 3.2.1) should be upgraded.
- Use TLS on the ALB (ACM certificate annotation) and IRSA for AWS access.

## 10. Java Notes
Java 8 images use `-XX:+UseContainerSupport` (supported in 8u191+) and `MaxRAMPercentage=75`; adjust `JAVA_OPTS` with the memory limit. Note the runtime image is a JDK (as explicitly requested), larger than a JRE.
