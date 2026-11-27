# Resortlite_Mono1 - Deployment Guide (Docker and AWS EKS)

Resortlite_Mono1 (ResortsLite) is a Spring Boot 2.7.18 REST application (Java 8, Maven, executable jar) that serves a
resort booking API on port 8080. Sessions and the booking cache are kept in Redis (Amazon ElastiCache), so any number
of pods can run behind the load balancer. The application keeps its bookings in the H2 in-memory database it was
already configured with (`spring.datasource.url=jdbc:h2:mem:resortdb`); that data is per pod and lost on restart.

## 1. Prerequisites and system requirements

| Tool | Use |
|---|---|
| Docker 20.10+ | build the image, run Docker Compose |
| AWS CLI v2 | ECR login, `aws eks update-kubeconfig` |
| kubectl (within one minor version of the cluster) | apply the manifests |
| eksctl | create the EKS cluster (optional if the cluster exists) |
| JDK 8 and Maven 3.8+ | only to build outside Docker |

IAM permissions for the user or role that deploys: `ecr:GetAuthorizationToken`, `ecr:CreateRepository`,
`ecr:DescribeRepositories`, `ecr:BatchCheckLayerAvailability`, `ecr:InitiateLayerUpload`, `ecr:UploadLayerPart`,
`ecr:CompleteLayerUpload`, `ecr:PutImage`, `eks:DescribeCluster`, and a mapping of that identity in the cluster's
`aws-auth` ConfigMap or EKS access entries (Kubernetes RBAC permission to create namespaces, secrets, deployments,
services and ingresses).

Resources per pod: requests 250m CPU / 512Mi memory, limits 500m CPU / 1Gi memory. A development machine needs about
2 GB of free memory to build the image.

External services (not created by these files): a Redis endpoint reachable from the pods (Amazon ElastiCache for Redis
in the same VPC as the EKS nodes, security group open on port 6379 from the nodes), and the payment API.

## 2. Local setup with Docker Compose

`docker-compose.yml` has one service, the application. Redis and the payment API are reached through environment
variables; start or point to them separately. The credential is read from your shell, never stored in the file.

```bash
export REDIS_HOST=host.docker.internal      # a Redis you run or reach separately
export REDIS_PASSWORD=changeit              # the Redis AUTH token; must not be empty for Compose
export PAYMENT_API_URL=http://payment.example.internal:9090/payments/charge
docker compose up --build
curl http://localhost:8080/actuator/health
```

Volumes `reports-data` and `backups-data` hold `/app/reports` and `/app/backups`.

## 3. Build and push the image

Run from the repository root.

```bash
./scripts/build-push.sh        # Linux, macOS
scripts\build-push.bat         # Windows
```

The script asks for the registry (1. AWS ECR, 2. Docker Hub), then the details of the chosen one (region, account id and
repository for ECR; username, password and repository for Docker Hub), then `IMAGE_TAG` (empty means `latest`). It builds
`docker build -f Dockerfile -t <registry>/<repo>:<tag> .`, logs in (`aws ecr get-login-password ... | docker login
--username AWS --password-stdin`), creates the ECR repository when missing and pushes. It prints the image URI to use in
the deployment. The image name is derived from the project name: `resortlite-mono1`.

## 4. AWS EKS deployment

### 4.1 Cluster setup and kubectl configuration

```bash
eksctl create cluster --name resortlite-cluster --region us-east-1 --nodes 2 --managed
# the ALB ingress needs the AWS Load Balancer Controller (IAM OIDC provider + controller installed with Helm)
eksctl utils associate-iam-oidc-provider --cluster resortlite-cluster --region us-east-1 --approve
aws eks update-kubeconfig --region us-east-1 --name resortlite-cluster
kubectl cluster-info
```

Install the AWS Load Balancer Controller following the AWS documentation; the ingress uses its annotations.

### 4.2 Deploy

```bash
./scripts/deploy-image.sh      # Linux, macOS
scripts\deploy-image.bat       # Windows
```

The script asks for the AWS region, the cluster name, the image URI (full path with registry and tag) and the value of
each environment variable (Enter keeps the default shown). `REDIS_PASSWORD` is asked without echo (in the .bat the input
is visible) and goes only into the Secret. It then:

1. configures kubectl (`aws eks update-kubeconfig`) and checks it with `kubectl cluster-info`;
2. fills the `{{...}}` placeholders in a temporary copy of `kubernetes/` with `sed` (so the repository files keep their
   placeholders);
3. applies `namespace.yaml`, creates the Secret `resortlite-mono1-secrets` with `kubectl create secret generic ...
   --dry-run=client -o yaml | kubectl apply -f -`, then applies `deployment.yaml`, `service.yaml`, `ingress.yaml`;
4. waits with `kubectl rollout status deployment/resortlite-mono1 -n resortlite-mono1` and prints
   `kubectl get pods,svc,ingress -n resortlite-mono1` and the load balancer address.

If the rollout fails: `kubectl rollout undo deployment/resortlite-mono1 -n resortlite-mono1`.

### 4.3 The manifests

| File | Content |
|---|---|
| `kubernetes/namespace.yaml` | Namespace `resortlite-mono1` with label `name: resortlite-mono1` |
| `kubernetes/deployment.yaml` | Deployment `resortlite-mono1`, 2 replicas, container port 8080, env placeholders (`{{IMAGE_URI}}`, `{{REDIS_HOST}}`, `{{PAYMENT_API_URL}}`, ...), `REDIS_PASSWORD` from the Secret, requests/limits, liveness and readiness probes, `emptyDir` volumes at `/app/reports` and `/app/backups` |
| `kubernetes/service.yaml` | Service `resortlite-mono1-service`, ClusterIP, port 80 to 8080 |
| `kubernetes/ingress.yaml` | Ingress `resortlite-mono1-ingress`, class `alb`, `internet-facing`, host `resortlite-mono1.example.com`, path `/` to the service on port 80 |

`emptyDir` volumes are lost when a pod is replaced and are not shared between pods: generated reports are temporary.
Mount a persistent volume (for example EFS) at `REPORT_PATH` if reports must survive.

## 5. Configuration management and environment variables

| Variable | Default | Meaning |
|---|---|---|
| `APP_PORT` | 8080 | HTTP port (`server.port`); keep `containerPort`, probes and Service in step if changed |
| `SPRING_PROFILES_ACTIVE` | prod | Spring profile |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_SSL` | localhost, 6379, false | Redis endpoint for sessions and cache; set `REDIS_SSL=true` for ElastiCache with in-transit encryption |
| `REDIS_PASSWORD` (Secret) | empty | Redis AUTH token |
| `PAYMENT_API_URL` | `http://payment-svc.internal:9090/payments/charge` | payment API endpoint (was a hard-coded IP) |
| `REPORT_PATH`, `BACKUP_PATH` | `/app/reports`, `/app/backups` | report and backup directories |
| `CACHE_TTL` | 1h | time-to-live of the booking cache entries |

## 6. Security considerations and best practices

- The Redis password lives only in the Kubernetes Secret `resortlite-mono1-secrets`; never put it in a ConfigMap, the image
  or the manifests. Consider AWS Secrets Manager with the External Secrets Operator.
- The container runs as the non-root user id 1001; keep it that way.
- Use TLS for Redis (`REDIS_SSL=true`) and put an ACM certificate on the ALB
  (`alb.ingress.kubernetes.io/certificate-arn`, `listen-ports`).
- Known weaknesses of the application that were not part of this work and should be fixed before production: log4j-core
  2.14.1 (CVE-2021-44228) and commons-collections 3.2.1 in `pom.xml`, SQL built by string concatenation, MD5 hashing,
  the H2 console enabled (`/h2-console`), and the plain `http://` URLs.
- Restrict the ElastiCache security group to the EKS nodes; use IRSA when pods need other AWS services.

## 7. Java notes

- Image: build stage `maven:3.8.6-openjdk-8-slim` runs `mvn clean package -DskipTests`; runtime stage `amazoncorretto:8`
  starts `java -jar` (the spring-boot-maven-plugin repackages a runnable jar). The entrypoint is
  `["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]`, so java receives SIGTERM and Spring shuts down gracefully.
- JVM settings: `JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"`. With the 1Gi limit,
  512m heap leaves room for metaspace, threads and buffers. If you raise the memory limit, raise `-Xmx` (or drop it and
  let `MaxRAMPercentage` size the heap); never set the heap near the container limit. Override `JAVA_OPTS` through the
  Deployment `env` if needed. `TZ=UTC` and UTF-8 are set.
- Spring profiles: `SPRING_PROFILES_ACTIVE` (default `prod`); the project has only `application.properties`, so add
  `application-<profile>.properties` files for profile-specific settings.
- Health endpoints (Spring Boot Actuator, same port as the application): `/actuator/health` (overall, includes Redis),
  `/actuator/health/liveness` (liveness probe) and `/actuator/health/readiness` (readiness probe and ALB health check). The probes
  deliberately do not depend on Redis so that a Redis outage does not restart the pods.
  Start-up takes about 15-30 s; the probes wait 60 s (liveness) and 30 s (readiness).

## 8. Troubleshooting

- **Pod failures**: `kubectl get pods -n resortlite-mono1`, `kubectl describe pod <pod> -n resortlite-mono1`,
  `kubectl logs <pod> -n resortlite-mono1`. `ImagePullBackOff`: wrong image URI or the node role lacks ECR read access
  (`AmazonEC2ContainerRegistryReadOnly`). `CrashLoopBackOff` or failing probes: check the logs; requests that use sessions or the
  cache fail with `RedisConnectionFailureException` when `REDIS_HOST`, the security group or `REDIS_SSL` is wrong.
  `OOMKilled`: raise the memory limit and `-Xmx` together.
- **Service issues**: `kubectl get endpoints resortlite-mono1-service -n resortlite-mono1` must list pod IPs; if empty, the
  pods are not ready or the selector `app: resortlite-mono1` does not match. Test with
  `kubectl port-forward svc/resortlite-mono1-service 8080:80 -n resortlite-mono1`.
- **Ingress problems**: `kubectl describe ingress resortlite-mono1-ingress -n resortlite-mono1`; no address means the AWS Load
  Balancer Controller is not installed or lacks IAM permissions (`kubectl logs -n kube-system deployment/aws-load-balancer-controller`);
  subnets need the tags `kubernetes.io/role/elb` (public). The rule matches the host `resortlite-mono1.example.com`: send that Host
  header or change the host.

## 9. Scaling and management

```bash
kubectl scale deployment/resortlite-mono1 --replicas=4 -n resortlite-mono1
kubectl autoscale deployment resortlite-mono1 --cpu-percent=70 --min=2 --max=10 -n resortlite-mono1   # HPA, needs metrics-server
kubectl set image deployment/resortlite-mono1 resortlite-mono1=<new image uri> -n resortlite-mono1      # rolling update
kubectl rollout status deployment/resortlite-mono1 -n resortlite-mono1
kubectl rollout history deployment/resortlite-mono1 -n resortlite-mono1
kubectl rollout undo deployment/resortlite-mono1 -n resortlite-mono1                                    # rollback
```

Because sessions and the cache are in Redis, pods can be added, removed or replaced without losing them. Bookings stored
in the in-memory H2 database are not shared between pods (the project's own configuration).
