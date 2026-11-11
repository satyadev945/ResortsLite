# ResortsLite - Deployment Guide (AWS EKS)

## 1. Overview
ResortsLite is a Spring Boot 2.7.18 / Java 8 web application (Maven, executable JAR) listening on port **8080**. Health is exposed through Spring Boot Actuator (`/actuator/health`, with probes enabled: `/actuator/health/liveness` and `/actuator/health/readiness`). External dependencies: Microsoft SQL Server (JDBC) and Redis / Amazon ElastiCache (Spring Session and booking cache). These are NOT part of the container artifacts.

Runtime base image: `eclipse-temurin:8-jre` (explicit). Builder image: `maven:3.9.4-eclipse-temurin-8`.

## 2. Prerequisites
- Docker 20+ (and Docker Compose v2 for local runs)
- AWS CLI v2 configured (`aws configure`) with permissions for ECR and EKS
- kubectl matching your cluster version, eksctl (optional, to create a cluster)
- An EKS cluster with the AWS Load Balancer Controller installed (required by the Ingress)
- Reachable SQL Server and Redis/ElastiCache from the cluster VPC

## 3. Local development with Docker Compose
```bash
export SPRING_DATASOURCE_URL='jdbc:sqlserver://<host>:1433;databaseName=ResortsDB;encrypt=false;trustServerCertificate=true'
export SPRING_DATASOURCE_USERNAME=<user>
export SPRING_DATASOURCE_PASSWORD=<password>
export REDIS_HOST=<redis-host>
docker compose up --build
```
App: http://localhost:8080/actuator/health

## 4. Build and push the image
Run from the module root:
```bash
./scripts/build-push.sh      # Linux/macOS
scripts\build-push.bat       # Windows
```
The script asks for a tag (sanitised to lowercase, default `latest`), the registry (1. AWS ECR, 2. Docker Hub), authenticates, creates the ECR repository when missing, builds and pushes. Note the printed image URI.

## 5. EKS setup
```bash
eksctl create cluster --name resortslite --region us-east-1 --nodes 2   # optional
aws eks update-kubeconfig --region us-east-1 --name resortslite
kubectl cluster-info
```
Install the AWS Load Balancer Controller (see AWS docs) before applying the Ingress. Ensure the node role can pull from ECR (AmazonEC2ContainerRegistryReadOnly).

## 6. Deploy
```bash
./scripts/deploy-image.sh     # or scripts\deploy-image.bat
```
The script prompts for region, cluster name, image URI and optional configuration, substitutes `{{IMAGE_URI}}` in a temporary copy of the manifests, then applies in order: namespace, ConfigMap/Secret (only if values were provided), deployment, service, ingress, then waits for rollout and prints the resources.

Manifests (`kubernetes/`):
| File | Purpose |
|------|---------|
| namespace.yaml | Namespace `resortslite` |
| deployment.yaml | 2 replicas, probes on 8080, requests 250m/512Mi, limits 500m/1Gi, emptyDir for reports/backups |
| service.yaml | ClusterIP, port 80 -> 8080 |
| ingress.yaml | ALB internet-facing ingress, host `resortslite.example.com` (edit to your domain) |

Get the URL: `kubectl get ingress -n resortslite`.

## 7. Configuration
The application.properties file hardcodes the datasource URL/credentials and downstream endpoints. Spring Boot relaxed binding lets environment variables override them, so supply real values at deploy time:

| Variable | Description | Store in |
|----------|-------------|----------|
| SPRING_DATASOURCE_URL / _USERNAME | SQL Server connection | ConfigMap `resortslite-config` |
| SPRING_DATASOURCE_PASSWORD | DB password | Secret `resortslite-secret` |
| REDIS_HOST, REDIS_PORT, REDIS_SSL_ENABLED | ElastiCache | ConfigMap |
| REDIS_PASSWORD | Redis auth | Secret |
| APP_PAYMENT_ENDPOINT, APP_INVENTORY_ENDPOINT, APP_NOTIFICATION_ENDPOINT | Downstream services | ConfigMap |
| SESSION_REDIS_NAMESPACE, SESSION_TIMEOUT, BOOKING_CACHE_KEY_PREFIX, BOOKING_CACHE_TTL_SECONDS | Session/cache tuning | ConfigMap |
| REPORT_BASE_PATH, REPORT_BACKUP_PATH | Report dirs (default /app/reports/, /app/backups/) | Deployment env |
| JAVA_OPTS | JVM flags (container support, MaxRAMPercentage=75) | Deployment env |

Update later with `kubectl edit configmap resortslite-config -n resortslite` followed by `kubectl rollout restart deployment/resortslite -n resortslite`.

## 8. Operations
- Scale: `kubectl scale deployment/resortslite --replicas=4 -n resortslite`
- HPA: `kubectl autoscale deployment resortslite --cpu-percent=70 --min=2 --max=6 -n resortslite` (requires metrics-server)
- Rolling update: re-run deploy script with a new image tag
- Rollback: `kubectl rollout undo deployment/resortslite -n resortslite`
- Logs: `kubectl logs deployment/resortslite -n resortslite`

## 9. Troubleshooting
- **ImagePullBackOff**: check image URI, ECR permissions on node role, repository region.
- **CrashLoopBackOff**: `kubectl logs <pod> -n resortslite --previous`; commonly DB URL/credentials wrong or SQL Server unreachable (security groups).
- **Probe failures**: startup can take 30-60 s; startupProbe allows up to 5 minutes. Confirm `/actuator/health/liveness` returns 200 via `kubectl port-forward`.
- **Sessions/cache errors**: verify REDIS_HOST/port, ElastiCache security group allows the node/pod SG, and REDIS_SSL_ENABLED matches in-transit encryption.
- **Ingress has no address**: ensure AWS Load Balancer Controller is running (`kubectl get pods -n kube-system`), and subnets are tagged for ELB.
- **Service not reachable**: `kubectl get endpoints resortslite-service -n resortslite`.

## 10. Security
- The container runs as non-root (UID 1001).
- The properties file contains a hardcoded DB password; rotate it and inject credentials via Kubernetes Secrets, ideally synced from AWS Secrets Manager (External Secrets / CSI driver).
- The pom pins vulnerable log4j-core 2.14.1 (Log4Shell) and commons-collections 3.2.1; upgrade before production.
- Only `health` is exposed by Actuator; add TLS on the ALB (ACM certificate + `alb.ingress.kubernetes.io/certificate-arn`, listen-ports 443).

## 11. Java notes
- Java 8 with `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0` (requires 8u191+, satisfied by the Temurin image).
- With a 1Gi limit, heap is about 768Mi; adjust via JAVA_OPTS.
- Timezone defaults to UTC (TZ and -Duser.timezone).
- Reports/backups use emptyDir and are lost on pod restart; mount EFS/PVC if persistence is needed.
