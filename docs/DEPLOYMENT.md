# Deployment Guide – Java msql (ResortsLite) on AWS EKS

## 1. Application overview
- Spring Boot 2.7.18, Java 8, Maven, executable JAR (`target/resortsLite-1.0.0.jar`)
- Port: 8080 (`server.port`, override with `SERVER_PORT`)
- Health: Actuator with probes enabled – `/actuator/health/liveness`, `/actuator/health/readiness`
- External dependencies: Microsoft SQL Server (JDBC), Redis / ElastiCache (Spring Session + booking cache), payment / inventory / notification HTTP services
- Base image: `eclipse-temurin:8-jre` (explicit); builder: `maven:3.9.4-eclipse-temurin-8`

## 2. Prerequisites
Docker 20+, AWS CLI v2 (configured credentials), kubectl, eksctl (optional), an EKS cluster with the AWS Load Balancer Controller installed, IAM permissions for ECR (`ecr:*` on the repo) and `eks:DescribeCluster`.

## 3. Local run with Docker Compose
```bash
export SPRING_DATASOURCE_URL='jdbc:sqlserver://<host>:1433;databaseName=ResortsDB;encrypt=false;trustServerCertificate=true'
export SPRING_DATASOURCE_USERNAME=<user>
export SPRING_DATASOURCE_PASSWORD=<password>
export REDIS_HOST=<redis-host>
docker compose up --build
curl http://localhost:8080/actuator/health
```
docker-compose.yml contains only the application; SQL Server and Redis must be provided externally.

## 4. Build and push the image
Run from the repository root:
```bash
./scripts/build-push.sh        # Linux/macOS
scripts\build-push.bat         # Windows
```
Choose 1 (AWS ECR – repository is auto-created) or 2 (Docker Hub). The script sanitises the image name (`java-msql`) and tag (default `latest`) and prints the full image URI.

## 5. Deploy to EKS
```bash
./scripts/deploy-image.sh      # or scripts\deploy-image.bat
```
The script prompts for region, cluster, image URI and application settings (Enter keeps defaults), then:
1. `aws eks update-kubeconfig` and `kubectl cluster-info`
2. Applies `namespace.yaml`
3. Creates secret `java-msql-secrets` (DB and Redis passwords – never written to manifests)
4. Applies `deployment.yaml`, `service.yaml`, `ingress.yaml` (placeholders replaced in a temp copy)
5. Waits for `kubectl rollout status deployment/java-msql -n java-msql` and prints the ALB address

### Manifests
| File | Purpose |
|---|---|
| `kubernetes/namespace.yaml` | Namespace `java-msql` |
| `kubernetes/deployment.yaml` | 2 replicas, rolling update, probes, resources 250m/512Mi – 500m/1Gi |
| `kubernetes/service.yaml` | ClusterIP, 80 → 8080 |
| `kubernetes/ingress.yaml` | ALB ingress (internet-facing, ip target type) |

## 6. Configuration
| Variable | Purpose | Default |
|---|---|---|
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | SQL Server connection (overrides hardcoded values in application.properties) | legacy values in properties |
| `REDIS_HOST/PORT/PASSWORD/SSL_ENABLED` | Spring Session and booking cache | localhost:6379 |
| `PAYMENT_API_URL` | Payment service | `http://payment-svc:9090/payments/charge` |
| `APP_INVENTORY_ENDPOINT`, `APP_NOTIFICATION_ENDPOINT` | Inventory / notification services | internal hosts |
| `REPORT_BASE_PATH`, `BACKUP_PATH` | Report and backup directories (emptyDir in K8s) | `reports/`, `backups/` |
| `JAVA_OPTS` | JVM flags | `-XX:MaxRAMPercentage=75.0 ...` |

**Important:** `application.properties` contains a hardcoded DB password. Always override it via environment/secret, and remove it from source control. Consider AWS Secrets Manager with External Secrets Operator or the Secrets Store CSI driver.

## 7. Troubleshooting
- `kubectl get pods -n java-msql`, `kubectl describe pod <pod> -n java-msql`, `kubectl logs <pod> -n java-msql`
- **ImagePullBackOff**: node role lacks ECR read access or URI is wrong.
- **CrashLoopBackOff**: usually DB/Redis unreachable – check security groups between EKS nodes and SQL Server/ElastiCache.
- **Readiness failing**: verify `/actuator/health/readiness` via `kubectl port-forward svc/java-msql-service 8080:80 -n java-msql`.
- **Ingress has no address**: confirm the AWS Load Balancer Controller is running (`kubectl get pods -n kube-system`) and subnets are tagged.
- Rollback: `kubectl rollout undo deployment/java-msql -n java-msql`

## 8. Scaling and management
```bash
kubectl scale deployment/java-msql --replicas=4 -n java-msql
kubectl autoscale deployment/java-msql --cpu-percent=70 --min=2 --max=6 -n java-msql
kubectl set image deployment/java-msql java-msql=<new-image> -n java-msql
kubectl rollout history deployment/java-msql -n java-msql
```
Sessions are stored in Redis, so replicas can scale freely; reports/backups use per-pod emptyDir volumes (use a PVC/EFS or S3 if persistence is required).

## 9. Security
Container runs as non-root (UID 1001); no secrets in images or manifests; use private ECR with scanning; upgrade vulnerable dependencies (log4j-core 2.14.1 – CVE-2021-44228, commons-collections 3.2.1) before production; restrict ingress with security groups / WAF and enable HTTPS with an ACM certificate annotation.

## 10. Java notes
Java 8u191+ is container-aware; heap is sized by `MaxRAMPercentage=75` of the 1Gi limit. Startup/liveness probes allow up to ~3 minutes for JVM and Hibernate `ddl-auto=update` initialization.
