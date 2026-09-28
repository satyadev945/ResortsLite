# ResortsLite - Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Local Development Setup](#local-development-setup)
4. [Docker Deployment](#docker-deployment)
5. [GCP GKE Deployment](#gcp-gke-deployment)
6. [Configuration Management](#configuration-management)
7. [Troubleshooting](#troubleshooting)
8. [Security Considerations](#security-considerations)
9. [Technology-Specific Notes](#technology-specific-notes)

---

## Overview

ResortsLite is a Spring Boot 2.7.18 application built with Java 8, designed for containerized deployment on Google Kubernetes Engine (GKE). This guide provides comprehensive instructions for deploying the application in various environments.

**Application Details:**
- **Framework**: Spring Boot 2.7.18
- **Java Version**: Java 8 (1.8)
- **Build Tool**: Maven
- **Application Port**: 8080 (configurable)
- **Health Endpoint**: `/actuator/health`
- **External Dependencies**: Oracle Database, Redis (session management), Payment/Inventory/Notification services

---

## Prerequisites

### Required Software

#### For Local Development:
- **Java Development Kit (JDK) 8** or higher
- **Maven 3.6+** for building the application
- **Docker Desktop** (latest version)
- **Docker Compose** (included with Docker Desktop)

#### For GCP GKE Deployment:
- **Google Cloud SDK (gcloud CLI)** - [Install Guide](https://cloud.google.com/sdk/docs/install)
- **kubectl** - Kubernetes command-line tool
- **Docker** - For building and pushing images
- **GCP Account** with appropriate permissions

### GCP Permissions Required:
- Kubernetes Engine Admin
- Artifact Registry Writer (if using Google Artifact Registry)
- Service Account User
- Compute Network Admin (for ingress configuration)

### External Services:
- **Oracle Database** - Connection details required
- **Redis Instance** - Google Cloud Memorystore for Redis recommended
- **Payment Service** - External payment processing API
- **Inventory Service** - Room inventory management service
- **Notification Service** - Email/SMS notification service

---

## Local Development Setup

### 1. Clone the Repository
```bash
git clone <repository-url>
cd agentic
```

### 2. Configure Application Properties
Edit `src/main/resources/application.properties` and update the following:

```properties
# Database Configuration
spring.datasource.url=jdbc:oracle:thin:@localhost:1521:ORCL
spring.datasource.username=your_username
spring.datasource.password=your_password

# Redis Configuration
spring.redis.host=localhost
spring.redis.port=6379
spring.redis.password=

# External Service Endpoints
PAYMENT_API_URL=http://localhost:9090/payments/charge
```

### 3. Build the Application
```bash
mvn clean package -DskipTests
```

### 4. Run Locally
```bash
java -jar target/resortsLite-1.0.0.jar
```

The application will start on `http://localhost:8080`

### 5. Verify Health
```bash
curl http://localhost:8080/actuator/health
```

Expected response:
```json
{"status":"UP"}
```

---

## Docker Deployment

### 1. Build Docker Image Locally
```bash
docker build -t resortslite:latest .
```

### 2. Run with Docker Compose

#### Configure Environment Variables
Create a `.env` file in the project root:

```env
# Database Configuration
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@oracle-db:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=your_password

# Redis Configuration
REDIS_HOST=redis-server
REDIS_PORT=6379
REDIS_PASSWORD=

# External Services
PAYMENT_API_URL=http://payment-service:9090/payments/charge
APP_INVENTORY_ENDPOINT=http://inventory-service:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://notification-service:7070/send
```

#### Start the Application
```bash
docker-compose up -d
```

#### View Logs
```bash
docker-compose logs -f resortslite
```

#### Stop the Application
```bash
docker-compose down
```

### 3. Access the Application
- Application: `http://localhost:8080`
- Health Check: `http://localhost:8080/actuator/health`

---

## GCP GKE Deployment

### Step 1: Prerequisites Setup

#### 1.1 Install Google Cloud SDK
```bash
# Install gcloud CLI (if not already installed)
# Visit: https://cloud.google.com/sdk/docs/install

# Authenticate with GCP
gcloud auth login

# Set your project
gcloud config set project YOUR_PROJECT_ID
```

#### 1.2 Install kubectl
```bash
# Install kubectl
gcloud components install kubectl

# Verify installation
kubectl version --client
```

#### 1.3 Create GKE Cluster (if not exists)
```bash
# Create a GKE cluster
gcloud container clusters create resortslite-cluster \
  --zone us-central1-a \
  --num-nodes 3 \
  --machine-type n1-standard-2 \
  --enable-autoscaling \
  --min-nodes 2 \
  --max-nodes 5

# Get cluster credentials
gcloud container clusters get-credentials resortslite-cluster \
  --zone us-central1-a
```

### Step 2: Build and Push Docker Image

#### Option A: Using Google Artifact Registry (Recommended)

##### 2.1 Create Artifact Registry Repository
```bash
# Create repository
gcloud artifacts repositories create resortslite-repo \
  --repository-format=docker \
  --location=us-central1 \
  --description="ResortsLite Docker images"

# Configure Docker authentication
gcloud auth configure-docker us-central1-docker.pkg.dev
```

##### 2.2 Build and Push Image
```bash
# Linux/macOS
chmod +x scripts/build-push.sh
./scripts/build-push.sh

# Windows
scripts\build-push.bat
```

Follow the prompts:
1. Select "1" for Google Artifact Registry
2. Enter your GCP Project ID
3. Enter region (e.g., `us-central1`)
4. Enter repository name (e.g., `resortslite-repo`)
5. Enter image tag (e.g., `v1.0.0` or `latest`)

#### Option B: Using Docker Hub

##### 2.1 Build and Push Image
```bash
# Linux/macOS
chmod +x scripts/build-push.sh
./scripts/build-push.sh

# Windows
scripts\build-push.bat
```

Follow the prompts:
1. Select "2" for Docker Hub
2. Enter your Docker Hub username
3. Enter your Docker Hub password/token
4. Enter image tag

### Step 3: Configure External Services

#### 3.1 Set Up Google Cloud Memorystore for Redis
```bash
# Create Redis instance
gcloud redis instances create resortslite-redis \
  --size=1 \
  --region=us-central1 \
  --redis-version=redis_6_x

# Get Redis host
gcloud redis instances describe resortslite-redis \
  --region=us-central1 \
  --format="get(host)"
```

#### 3.2 Configure Database Access
Ensure your Oracle Database is accessible from GKE:
- Use Cloud SQL Proxy for Cloud SQL databases
- Configure VPC peering for external databases
- Set up appropriate firewall rules

### Step 4: Deploy to GKE

#### 4.1 Run Deployment Script
```bash
# Linux/macOS
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh

# Windows
scripts\deploy-image.bat
```

#### 4.2 Provide Configuration
The script will prompt for:

**GCP Configuration:**
- GCP Project ID
- GCP Zone (e.g., `us-central1-a`)
- GKE Cluster Name

**Docker Image:**
- Full image URI (e.g., `us-central1-docker.pkg.dev/project/repo/resortslite:v1.0.0`)

**Application Configuration:**
- Oracle Database URL
- Database username and password
- Redis host, port, and password
- Payment API URL
- Inventory service endpoint
- Notification service endpoint

### Step 5: Verify Deployment

#### 5.1 Check Pod Status
```bash
kubectl get pods -n resortslite
```

Expected output:
```
NAME                           READY   STATUS    RESTARTS   AGE
resortslite-xxxxxxxxxx-xxxxx   1/1     Running   0          2m
resortslite-xxxxxxxxxx-xxxxx   1/1     Running   0          2m
```

#### 5.2 Check Service
```bash
kubectl get svc -n resortslite
```

#### 5.3 Check Ingress
```bash
kubectl get ingress -n resortslite
```

#### 5.4 View Logs
```bash
# View logs from all pods
kubectl logs -f deployment/resortslite -n resortslite

# View logs from specific pod
kubectl logs -f <pod-name> -n resortslite
```

### Step 6: Access the Application

#### Option A: Port Forwarding (for testing)
```bash
kubectl port-forward -n resortslite svc/resortslite-service 8080:80
```
Access at: `http://localhost:8080`

#### Option B: Ingress (production)
```bash
# Get ingress IP address
kubectl get ingress resortslite-ingress -n resortslite

# Wait for IP to be assigned (may take 5-10 minutes)
# Access at: http://<INGRESS_IP>
```

#### Option C: Load Balancer (alternative)
Update `kubernetes/service.yaml` to use `type: LoadBalancer`:
```yaml
spec:
  type: LoadBalancer
```

Then get the external IP:
```bash
kubectl get svc resortslite-service -n resortslite
```

---

## Configuration Management

### Environment Variables

The application uses the following environment variables:

#### Application Configuration
- `SERVER_PORT` - Application port (default: 8080)
- `SPRING_PROFILES_ACTIVE` - Active Spring profile (default: docker)

#### Database Configuration
- `SPRING_DATASOURCE_URL` - Oracle JDBC URL
- `SPRING_DATASOURCE_USERNAME` - Database username
- `SPRING_DATASOURCE_PASSWORD` - Database password
- `SPRING_DATASOURCE_DRIVER_CLASS_NAME` - JDBC driver class

#### Redis Configuration
- `REDIS_HOST` - Redis server hostname
- `REDIS_PORT` - Redis server port (default: 6379)
- `REDIS_PASSWORD` - Redis authentication password

#### External Services
- `PAYMENT_API_URL` - Payment service endpoint
- `APP_INVENTORY_ENDPOINT` - Inventory service endpoint
- `APP_NOTIFICATION_ENDPOINT` - Notification service endpoint

#### JVM Configuration
- `JAVA_OPTS` - JVM options (default: `-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`)

### Using Kubernetes Secrets

For production deployments, use Kubernetes Secrets for sensitive data:

#### Create Secrets
```bash
# Database credentials
kubectl create secret generic db-credentials \
  --from-literal=username=admin \
  --from-literal=password=your_password \
  -n resortslite

# Redis credentials
kubectl create secret generic redis-credentials \
  --from-literal=password=your_redis_password \
  -n resortslite
```

#### Update deployment.yaml
```yaml
env:
- name: SPRING_DATASOURCE_USERNAME
  valueFrom:
    secretKeyRef:
      name: db-credentials
      key: username
- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef:
      name: db-credentials
      key: password
```

### Using ConfigMaps

For non-sensitive configuration:

```bash
# Create ConfigMap
kubectl create configmap app-config \
  --from-literal=payment.url=http://payment-service:9090 \
  -n resortslite
```

---

## Troubleshooting

### Common Issues and Solutions

#### 1. Pod Not Starting

**Symptoms:**
```bash
kubectl get pods -n resortslite
# Shows: CrashLoopBackOff or ImagePullBackOff
```

**Solutions:**

**ImagePullBackOff:**
```bash
# Check image name and tag
kubectl describe pod <pod-name> -n resortslite

# Verify image exists in registry
gcloud artifacts docker images list us-central1-docker.pkg.dev/PROJECT/REPO

# Check image pull secrets
kubectl get secrets -n resortslite
```

**CrashLoopBackOff:**
```bash
# Check logs
kubectl logs <pod-name> -n resortslite

# Common causes:
# - Database connection failure
# - Redis connection failure
# - Missing environment variables
# - JVM memory issues
```

#### 2. Database Connection Issues

**Symptoms:**
- Application logs show connection timeout
- Health check fails

**Solutions:**
```bash
# Verify database connectivity from pod
kubectl exec -it <pod-name> -n resortslite -- /bin/sh
# Try: telnet db-host 1521

# Check database credentials
kubectl get secret db-credentials -n resortslite -o yaml

# Verify network policies
kubectl get networkpolicies -n resortslite
```

#### 3. Redis Connection Issues

**Symptoms:**
- Session management fails
- Application logs show Redis connection errors

**Solutions:**
```bash
# Verify Redis instance is running
gcloud redis instances describe resortslite-redis --region=us-central1

# Check Redis host in deployment
kubectl get deployment resortslite -n resortslite -o yaml | grep REDIS_HOST

# Test Redis connectivity
kubectl exec -it <pod-name> -n resortslite -- /bin/sh
# Try: telnet redis-host 6379
```

#### 4. Ingress Not Working

**Symptoms:**
- Cannot access application via ingress IP
- Ingress IP not assigned

**Solutions:**
```bash
# Check ingress status
kubectl describe ingress resortslite-ingress -n resortslite

# Verify backend service
kubectl get svc resortslite-service -n resortslite

# Check ingress controller logs
kubectl logs -n kube-system -l k8s-app=glbc

# Verify firewall rules
gcloud compute firewall-rules list
```

#### 5. High Memory Usage

**Symptoms:**
- Pods being OOMKilled
- Application performance degradation

**Solutions:**
```bash
# Check resource usage
kubectl top pods -n resortslite

# Increase memory limits in deployment.yaml
resources:
  limits:
    memory: "2Gi"  # Increase from 1Gi

# Adjust JVM heap size
JAVA_OPTS: "-Xmx1536m -Xms768m"

# Apply changes
kubectl apply -f kubernetes/deployment.yaml
```

#### 6. Health Check Failures

**Symptoms:**
- Pods restarting frequently
- Readiness probe failures

**Solutions:**
```bash
# Check health endpoint manually
kubectl port-forward <pod-name> -n resortslite 8080:8080
curl http://localhost:8080/actuator/health

# Increase probe timeouts in deployment.yaml
livenessProbe:
  initialDelaySeconds: 120  # Increase from 90
  timeoutSeconds: 10
  failureThreshold: 5  # Increase from 3
```

### Debugging Commands

```bash
# Get detailed pod information
kubectl describe pod <pod-name> -n resortslite

# View pod events
kubectl get events -n resortslite --sort-by='.lastTimestamp'

# Execute commands in pod
kubectl exec -it <pod-name> -n resortslite -- /bin/sh

# View resource usage
kubectl top pods -n resortslite
kubectl top nodes

# Check deployment status
kubectl rollout status deployment/resortslite -n resortslite

# View deployment history
kubectl rollout history deployment/resortslite -n resortslite

# Rollback deployment
kubectl rollout undo deployment/resortslite -n resortslite
```

---

## Security Considerations

### 1. Use Secrets for Sensitive Data
Never hardcode credentials in configuration files. Use Kubernetes Secrets or GCP Secret Manager.

```bash
# Create secret from file
kubectl create secret generic app-secrets \
  --from-file=./secrets.properties \
  -n resortslite
```

### 2. Enable Network Policies
Restrict pod-to-pod communication:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: resortslite-netpol
  namespace: resortslite
spec:
  podSelector:
    matchLabels:
      app: resortslite
  policyTypes:
  - Ingress
  - Egress
  ingress:
  - from:
    - namespaceSelector:
        matchLabels:
          name: ingress-nginx
    ports:
    - protocol: TCP
      port: 8080
```

### 3. Use Workload Identity
For GCP service access:

```bash
# Create service account
gcloud iam service-accounts create resortslite-sa

# Bind Kubernetes service account
kubectl annotate serviceaccount default \
  iam.gke.io/gcp-service-account=resortslite-sa@PROJECT.iam.gserviceaccount.com \
  -n resortslite
```

### 4. Enable Pod Security Standards
```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: resortslite
  labels:
    pod-security.kubernetes.io/enforce: restricted
```

### 5. Regular Security Updates
- Keep base images updated
- Scan images for vulnerabilities
- Update dependencies regularly

```bash
# Scan image with gcloud
gcloud artifacts docker images scan IMAGE_URI
```

### 6. TLS/SSL Configuration
Configure HTTPS for ingress:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: resortslite-ingress
  annotations:
    kubernetes.io/ingress.class: "gce"
    networking.gke.io/managed-certificates: "resortslite-cert"
spec:
  tls:
  - hosts:
    - resortslite.example.com
    secretName: resortslite-tls
```

---

## Technology-Specific Notes

### Spring Boot Configuration

#### 1. Spring Profiles
The application uses Spring profiles for environment-specific configuration:

- `default` - Local development
- `docker` - Docker/Kubernetes deployment
- `prod` - Production environment

Activate profile via environment variable:
```bash
SPRING_PROFILES_ACTIVE=docker
```

#### 2. Spring Boot Actuator
Health endpoints are exposed for monitoring:

- `/actuator/health` - Application health status
- `/actuator/info` - Application information

Configure in `application.properties`:
```properties
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=when-authorized
```

#### 3. Session Management with Redis
The application uses Spring Session with Redis for distributed session management:

```properties
spring.session.store-type=redis
spring.session.redis.flush-mode=on_save
spring.session.redis.namespace=spring:session
```

This enables:
- Horizontal scaling with multiple pods
- Session persistence across pod restarts
- Shared sessions across instances

#### 4. JPA/Hibernate Configuration
Oracle-specific configuration:

```properties
spring.jpa.database-platform=org.hibernate.dialect.Oracle12cDialect
spring.jpa.show-sql=true
spring.jpa.hibernate.ddl-auto=update
```

**Note:** For production, set `ddl-auto=validate` to prevent schema changes.

### Java 8 Considerations

#### 1. JVM Memory Settings
Optimized for containerized environments:

```bash
JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
```

- `UseContainerSupport` - Enables container-aware JVM
- `MaxRAMPercentage` - Limits heap to 75% of container memory

#### 2. Garbage Collection
For better performance in containers:

```bash
JAVA_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

#### 3. Monitoring and Debugging
Enable JMX for monitoring:

```bash
JAVA_OPTS="-Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=9010 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false"
```

### Maven Build Optimization

#### 1. Dependency Caching
The Dockerfile uses layer caching for faster builds:

```dockerfile
# Copy pom.xml first
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Then copy source
COPY src ./src
RUN mvn clean package -DskipTests -B
```

#### 2. Skip Tests in Docker Build
Tests are skipped during Docker build for speed:

```bash
mvn clean package -DskipTests
```

Run tests separately before building:
```bash
mvn test
```

---

## Scaling and Performance

### Horizontal Pod Autoscaling

Create HPA for automatic scaling:

```bash
kubectl autoscale deployment resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortslite
```

Or use YAML:

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: resortslite-hpa
  namespace: resortslite
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: resortslite
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
```

### Performance Tuning

#### 1. Connection Pooling
Configure HikariCP (default in Spring Boot):

```properties
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000
```

#### 2. Redis Connection Pool
```properties
spring.redis.lettuce.pool.max-active=8
spring.redis.lettuce.pool.max-idle=8
spring.redis.lettuce.pool.min-idle=2
```

#### 3. JVM Tuning
```bash
JAVA_OPTS="-Xmx1g -Xms512m \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+ParallelRefProcEnabled \
  -XX:+UseStringDeduplication"
```

---

## Monitoring and Logging

### 1. View Application Logs
```bash
# Stream logs
kubectl logs -f deployment/resortslite -n resortslite

# View logs from all pods
kubectl logs -l app=resortslite -n resortslite --tail=100

# View previous container logs (after crash)
kubectl logs <pod-name> -n resortslite --previous
```

### 2. GCP Cloud Logging
Logs are automatically sent to Cloud Logging:

```bash
# View logs in Cloud Console
gcloud logging read "resource.type=k8s_container AND resource.labels.namespace_name=resortslite" \
  --limit 50 \
  --format json
```

### 3. Metrics and Monitoring
Use GCP Cloud Monitoring:

```bash
# View metrics in Cloud Console
# Navigate to: Monitoring > Dashboards > GKE
```

### 4. Spring Boot Actuator Metrics
Expose additional metrics:

```properties
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.metrics.export.prometheus.enabled=true
```

---

## Maintenance and Updates

### Rolling Updates

```bash
# Update image
kubectl set image deployment/resortslite \
  resortslite=us-central1-docker.pkg.dev/project/repo/resortslite:v2.0.0 \
  -n resortslite

# Monitor rollout
kubectl rollout status deployment/resortslite -n resortslite

# Pause rollout
kubectl rollout pause deployment/resortslite -n resortslite

# Resume rollout
kubectl rollout resume deployment/resortslite -n resortslite
```

### Rollback

```bash
# Rollback to previous version
kubectl rollout undo deployment/resortslite -n resortslite

# Rollback to specific revision
kubectl rollout undo deployment/resortslite --to-revision=2 -n resortslite

# View rollout history
kubectl rollout history deployment/resortslite -n resortslite
```

### Backup and Disaster Recovery

#### 1. Backup Kubernetes Resources
```bash
# Export all resources
kubectl get all -n resortslite -o yaml > backup.yaml

# Backup specific resources
kubectl get deployment,service,ingress -n resortslite -o yaml > k8s-backup.yaml
```

#### 2. Database Backups
Implement regular database backups according to your Oracle Database backup strategy.

#### 3. Redis Persistence
Configure Redis persistence in Cloud Memorystore:
```bash
gcloud redis instances update resortslite-redis \
  --region=us-central1 \
  --persistence-mode=RDB
```

---

## Additional Resources

### Documentation Links
- [Spring Boot Documentation](https://docs.spring.io/spring-boot/docs/2.7.x/reference/html/)
- [Google Kubernetes Engine Documentation](https://cloud.google.com/kubernetes-engine/docs)
- [Kubernetes Documentation](https://kubernetes.io/docs/)
- [Docker Documentation](https://docs.docker.com/)

### Support and Contact
For issues or questions:
1. Check the troubleshooting section above
2. Review application logs
3. Contact your DevOps team
4. Refer to GCP support documentation

---

## Appendix

### A. Complete Environment Variable Reference

| Variable | Description | Default | Required |
|----------|-------------|---------|----------|
| SERVER_PORT | Application HTTP port | 8080 | No |
| SPRING_PROFILES_ACTIVE | Active Spring profile | docker | No |
| SPRING_DATASOURCE_URL | Oracle JDBC URL | - | Yes |
| SPRING_DATASOURCE_USERNAME | Database username | - | Yes |
| SPRING_DATASOURCE_PASSWORD | Database password | - | Yes |
| REDIS_HOST | Redis server hostname | localhost | Yes |
| REDIS_PORT | Redis server port | 6379 | No |
| REDIS_PASSWORD | Redis password | - | No |
| PAYMENT_API_URL | Payment service URL | - | Yes |
| APP_INVENTORY_ENDPOINT | Inventory service URL | - | Yes |
| APP_NOTIFICATION_ENDPOINT | Notification service URL | - | Yes |
| JAVA_OPTS | JVM options | See above | No |

### B. Port Reference

| Port | Service | Description |
|------|---------|-------------|
| 8080 | Application | Main HTTP port |
| 1521 | Oracle DB | Database connection |
| 6379 | Redis | Session storage |
| 9090 | Payment Service | External payment API |
| 8081 | Inventory Service | Room inventory API |
| 7070 | Notification Service | Notification API |

### C. Health Check Endpoints

| Endpoint | Description | Response |
|----------|-------------|----------|
| /actuator/health | Application health | {"status":"UP"} |
| /actuator/info | Application info | Application metadata |

---

**Document Version**: 1.0.0  
**Last Updated**: 2024  
**Application Version**: ResortsLite 1.0.0
