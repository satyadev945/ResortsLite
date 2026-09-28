# ResortsLite - Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Local Development Setup](#local-development-setup)
4. [Docker Deployment](#docker-deployment)
5. [AWS EKS Deployment](#aws-eks-deployment)
6. [Configuration Management](#configuration-management)
7. [Troubleshooting](#troubleshooting)
8. [Security Considerations](#security-considerations)
9. [Technology-Specific Notes](#technology-specific-notes)

---

## Overview

ResortsLite is a Spring Boot 2.7.x application built with Java 8, designed for containerized deployment on AWS EKS (Elastic Kubernetes Service). This guide provides comprehensive instructions for building, deploying, and managing the application in various environments.

**Application Details:**
- **Framework:** Spring Boot 2.7.18
- **Java Version:** 1.8
- **Build Tool:** Maven
- **Application Port:** 8080
- **Management Port:** 8081
- **Health Endpoint:** `/actuator/health`

---

## Prerequisites

### Required Tools

#### For Local Development:
- **Docker Desktop** (v20.10+)
  - Download: https://www.docker.com/products/docker-desktop
- **Docker Compose** (v2.0+)
  - Included with Docker Desktop
- **Java Development Kit (JDK) 8**
  - Download: https://adoptium.net/
- **Maven** (v3.6+)
  - Download: https://maven.apache.org/download.cgi

#### For AWS EKS Deployment:
- **AWS CLI** (v2.0+)
  - Installation: https://aws.amazon.com/cli/
  - Configure: `aws configure`
- **kubectl** (v1.24+)
  - Installation: https://kubernetes.io/docs/tasks/tools/
- **eksctl** (optional, for cluster creation)
  - Installation: https://eksctl.io/
- **AWS IAM Permissions:**
  - EKS cluster access (eks:DescribeCluster, eks:ListClusters)
  - ECR repository access (ecr:GetAuthorizationToken, ecr:BatchCheckLayerAvailability, ecr:GetDownloadUrlForLayer, ecr:BatchGetImage, ecr:PutImage)
  - IAM role for EKS service account

### System Requirements
- **Memory:** Minimum 4GB RAM (8GB recommended)
- **Disk Space:** 10GB free space
- **Operating System:** Linux, macOS, or Windows 10/11

---

## Local Development Setup

### 1. Clone the Repository
```bash
git clone <repository-url>
cd Mono-resortlite-classic-CMP
```

### 2. Build the Application Locally
```bash
# Using Maven
mvn clean package -DskipTests

# Verify the build
ls -lh target/*.jar
```

### 3. Run Locally (Without Docker)
```bash
# Set environment variables
export REDIS_HOST=localhost
export REDIS_PORT=6379

# Run the application
java -jar target/resortsLite-1.0.0.jar

# Access the application
# http://localhost:8080
# http://localhost:8080/actuator/health
```

### 4. Run with Docker Compose
```bash
# Build and start the application
docker-compose up --build

# Run in detached mode
docker-compose up -d

# View logs
docker-compose logs -f

# Stop the application
docker-compose down
```

---

## Docker Deployment

### Build Docker Image

#### Linux/macOS:
```bash
# Make scripts executable
chmod +x scripts/build-push.sh

# Run build script
./scripts/build-push.sh
```

#### Windows:
```cmd
# Run build script
scripts\build-push.bat
```

### Script Workflow:
1. **Select Registry Type:**
   - Option 1: AWS ECR (Elastic Container Registry)
   - Option 2: Docker Hub

2. **Provide Registry Details:**
   - For ECR: AWS Region, Account ID, Repository Name
   - For Docker Hub: Username, Password/Token

3. **Build and Push:**
   - Script builds the Docker image
   - Authenticates with the selected registry
   - Pushes the image to the registry

### Manual Docker Build (Alternative):
```bash
# Build image
docker build -t resortslite:latest .

# Tag for registry
docker tag resortslite:latest <registry-url>/resortslite:latest

# Push to registry
docker push <registry-url>/resortslite:latest
```

---

## AWS EKS Deployment

### Prerequisites Setup

#### 1. Create EKS Cluster (if not exists)
```bash
# Using eksctl (recommended)
eksctl create cluster \
  --name resortslite-cluster \
  --region us-east-1 \
  --nodegroup-name standard-workers \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 4 \
  --managed

# Verify cluster
aws eks describe-cluster --name resortslite-cluster --region us-east-1
```

#### 2. Configure kubectl
```bash
# Update kubeconfig
aws eks update-kubeconfig --region us-east-1 --name resortslite-cluster

# Verify connectivity
kubectl cluster-info
kubectl get nodes
```

#### 3. Install AWS Load Balancer Controller (for Ingress)
```bash
# Create IAM policy
curl -o iam_policy.json https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v2.4.7/docs/install/iam_policy.json

aws iam create-policy \
  --policy-name AWSLoadBalancerControllerIAMPolicy \
  --policy-document file://iam_policy.json

# Install controller using Helm
helm repo add eks https://aws.github.io/eks-charts
helm repo update

helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=resortslite-cluster \
  --set serviceAccount.create=true \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Deploy Application to EKS

#### Linux/macOS:
```bash
# Make script executable
chmod +x scripts/deploy-image.sh

# Run deployment script
./scripts/deploy-image.sh
```

#### Windows:
```cmd
# Run deployment script
scripts\deploy-image.bat
```

### Deployment Script Workflow:
1. **AWS Configuration:**
   - Enter AWS Region (e.g., us-east-1)
   - Enter EKS Cluster Name

2. **Docker Image:**
   - Provide full Docker image URI from ECR or Docker Hub

3. **Application Configuration:**
   - Database connection details
   - Redis connection details
   - External service endpoints

4. **Deployment Process:**
   - Configures kubectl for EKS
   - Updates Kubernetes manifests with provided values
   - Applies manifests in order: namespace → deployment → service → ingress
   - Waits for deployment rollout
   - Displays deployment status

### Manual Deployment (Alternative):

#### 1. Update Manifests
Edit `kubernetes/deployment.yaml` and replace placeholders:
- `{{IMAGE_URI}}`: Your Docker image URI
- `{{SPRING_DATASOURCE_URL}}`: Database connection string
- `{{REDIS_HOST}}`: Redis hostname
- Other environment variables as needed

#### 2. Apply Manifests
```bash
# Create namespace
kubectl apply -f kubernetes/namespace.yaml

# Deploy application
kubectl apply -f kubernetes/deployment.yaml

# Create service
kubectl apply -f kubernetes/service.yaml

# Create ingress
kubectl apply -f kubernetes/ingress.yaml
```

#### 3. Verify Deployment
```bash
# Check pods
kubectl get pods -n resortslite

# Check services
kubectl get svc -n resortslite

# Check ingress
kubectl get ingress -n resortslite

# View logs
kubectl logs -f deployment/resortslite -n resortslite
```

### Access the Application

#### Port Forwarding (for testing):
```bash
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite
# Access: http://localhost:8080
```

#### Via Ingress (production):
```bash
# Get ingress URL
kubectl get ingress resortslite-ingress -n resortslite

# Access via the ALB DNS name
# Example: http://k8s-resortsl-resortsl-abc123.us-east-1.elb.amazonaws.com
```

---

## Configuration Management

### Environment Variables

The application uses the following environment variables:

#### Database Configuration:
- `SPRING_DATASOURCE_URL`: JDBC connection string
- `SPRING_DATASOURCE_USERNAME`: Database username
- `SPRING_DATASOURCE_PASSWORD`: Database password

#### Redis Configuration:
- `REDIS_HOST`: Redis server hostname
- `REDIS_PORT`: Redis server port (default: 6379)
- `REDIS_PASSWORD`: Redis authentication password

#### External Services:
- `PAYMENT_API_URL`: Payment service endpoint
- `APP_PAYMENT_ENDPOINT`: Legacy payment endpoint
- `APP_INVENTORY_ENDPOINT`: Inventory service endpoint
- `APP_NOTIFICATION_ENDPOINT`: Notification service endpoint

#### JVM Configuration:
- `JAVA_OPTS`: JVM options (default: `-Xmx512m -Xms256m -XX:+UseContainerSupport`)

### Kubernetes ConfigMaps and Secrets

#### Create ConfigMap:
```bash
kubectl create configmap resortslite-config \
  --from-literal=REDIS_HOST=redis.example.com \
  --from-literal=REDIS_PORT=6379 \
  -n resortslite
```

#### Create Secret:
```bash
kubectl create secret generic resortslite-secrets \
  --from-literal=SPRING_DATASOURCE_PASSWORD=mypassword \
  --from-literal=REDIS_PASSWORD=redispassword \
  -n resortslite
```

#### Update Deployment to Use ConfigMap/Secret:
```yaml
envFrom:
- configMapRef:
    name: resortslite-config
- secretRef:
    name: resortslite-secrets
```

---

## Troubleshooting

### Common Issues

#### 1. Pod Not Starting
```bash
# Check pod status
kubectl get pods -n resortslite

# Describe pod for events
kubectl describe pod <pod-name> -n resortslite

# Check logs
kubectl logs <pod-name> -n resortslite
```

**Common Causes:**
- Image pull errors (check ECR permissions)
- Insufficient resources (check node capacity)
- Configuration errors (check environment variables)

#### 2. Health Check Failures
```bash
# Check health endpoint
kubectl exec -it <pod-name> -n resortslite -- curl http://localhost:8080/actuator/health

# Check readiness probe
kubectl describe pod <pod-name> -n resortslite | grep -A 10 Readiness
```

**Solutions:**
- Increase `initialDelaySeconds` for slow startup
- Verify health endpoint is accessible
- Check application logs for errors

#### 3. Service Not Accessible
```bash
# Check service endpoints
kubectl get endpoints -n resortslite

# Test service connectivity
kubectl run test-pod --image=curlimages/curl -it --rm -- curl http://resortslite-service.resortslite.svc.cluster.local
```

**Solutions:**
- Verify pod labels match service selector
- Check network policies
- Verify ingress configuration

#### 4. Ingress Not Working
```bash
# Check ingress status
kubectl describe ingress resortslite-ingress -n resortslite

# Check ALB controller logs
kubectl logs -n kube-system deployment/aws-load-balancer-controller
```

**Solutions:**
- Verify AWS Load Balancer Controller is installed
- Check IAM permissions for ALB controller
- Verify security groups allow traffic

### Debugging Commands

```bash
# Get all resources in namespace
kubectl get all -n resortslite

# Check events
kubectl get events -n resortslite --sort-by='.lastTimestamp'

# Execute commands in pod
kubectl exec -it <pod-name> -n resortslite -- /bin/sh

# Port forward for debugging
kubectl port-forward <pod-name> 8080:8080 -n resortslite

# View resource usage
kubectl top pods -n resortslite
kubectl top nodes
```

---

## Security Considerations

### 1. Image Security
- **Use Official Base Images:** amazoncorretto:8 (verified by AWS)
- **Scan Images:** Use AWS ECR image scanning
  ```bash
  aws ecr start-image-scan --repository-name resortslite --image-id imageTag=latest
  ```
- **Update Dependencies:** Regularly update base images and dependencies

### 2. Container Security
- **Non-Root User:** Application runs as non-root user `appuser`
- **Read-Only Filesystem:** Consider adding `readOnlyRootFilesystem: true`
- **Security Context:**
  ```yaml
  securityContext:
    runAsNonRoot: true
    runAsUser: 1000
    capabilities:
      drop:
      - ALL
  ```

### 3. Network Security
- **Network Policies:** Restrict pod-to-pod communication
- **Service Mesh:** Consider using AWS App Mesh for advanced traffic management
- **TLS/SSL:** Enable HTTPS on ingress with ACM certificates

### 4. Secrets Management
- **AWS Secrets Manager:** Store sensitive data in AWS Secrets Manager
- **External Secrets Operator:** Sync secrets from AWS to Kubernetes
- **Never Commit Secrets:** Use `.gitignore` for sensitive files

### 5. RBAC (Role-Based Access Control)
```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: resortslite-sa
  namespace: resortslite
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: resortslite-role
  namespace: resortslite
rules:
- apiGroups: [""]
  resources: ["configmaps", "secrets"]
  verbs: ["get", "list"]
```

---

## Technology-Specific Notes

### Spring Boot Configuration

#### 1. Actuator Endpoints
The application exposes Spring Boot Actuator endpoints for monitoring:
- **Health:** `/actuator/health` - Application health status
- **Info:** `/actuator/info` - Application information
- **Metrics:** `/actuator/metrics` - Application metrics (if enabled)

#### 2. Spring Profiles
Use Spring profiles for environment-specific configuration:
```bash
# Set profile via environment variable
SPRING_PROFILES_ACTIVE=production

# Or via JVM argument
JAVA_OPTS="-Dspring.profiles.active=production"
```

#### 3. External Configuration
Spring Boot supports external configuration sources:
- Environment variables (highest priority)
- ConfigMaps mounted as volumes
- application.properties/yml files

#### 4. Session Management
The application uses Spring Session with Redis for distributed session storage:
- Sessions are stored in Redis for horizontal scaling
- Configure Redis connection via environment variables
- Session timeout: 1800 seconds (30 minutes)

### Java 8 Considerations

#### 1. JVM Memory Settings
For containerized Java 8 applications:
```bash
JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
```

#### 2. Garbage Collection
Consider using G1GC for better performance:
```bash
JAVA_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

#### 3. Container Awareness
Java 8u191+ includes container awareness:
- Automatically detects container memory limits
- Adjusts heap size accordingly
- Use `-XX:+UseContainerSupport` to enable

### Maven Build Optimization

#### 1. Dependency Caching
The Dockerfile uses layer caching for faster builds:
```dockerfile
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests
```

#### 2. Skip Tests in Docker Build
Tests are skipped during Docker build for speed:
```bash
mvn clean package -DskipTests
```

Run tests separately in CI/CD pipeline.

---

## Scaling and High Availability

### Horizontal Pod Autoscaler (HPA)

Create HPA for automatic scaling:
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

Apply HPA:
```bash
kubectl apply -f hpa.yaml
kubectl get hpa -n resortslite
```

### Rolling Updates

Update deployment with zero downtime:
```bash
# Update image
kubectl set image deployment/resortslite resortslite=<new-image-uri> -n resortslite

# Check rollout status
kubectl rollout status deployment/resortslite -n resortslite

# Rollback if needed
kubectl rollout undo deployment/resortslite -n resortslite
```

---

## Monitoring and Logging

### CloudWatch Integration

#### 1. Container Insights
Enable CloudWatch Container Insights for EKS:
```bash
aws eks update-cluster-config \
  --name resortslite-cluster \
  --logging '{"clusterLogging":[{"types":["api","audit","authenticator","controllerManager","scheduler"],"enabled":true}]}'
```

#### 2. Application Logs
View logs in CloudWatch:
```bash
# Install Fluent Bit for log forwarding
kubectl apply -f https://raw.githubusercontent.com/aws-samples/amazon-cloudwatch-container-insights/latest/k8s-deployment-manifest-templates/deployment-mode/daemonset/container-insights-monitoring/fluent-bit/fluent-bit.yaml
```

### Prometheus and Grafana

Deploy monitoring stack:
```bash
# Add Prometheus Helm repo
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

# Install Prometheus
helm install prometheus prometheus-community/kube-prometheus-stack -n monitoring --create-namespace

# Access Grafana
kubectl port-forward svc/prometheus-grafana 3000:80 -n monitoring
# Default credentials: admin/prom-operator
```

---

## Backup and Disaster Recovery

### Database Backups
- Use AWS RDS automated backups
- Configure backup retention period
- Test restore procedures regularly

### Application State
- Redis data persistence (if using ElastiCache)
- ConfigMaps and Secrets backup
- Velero for Kubernetes resource backup

---

## Cost Optimization

### 1. Right-Sizing
- Monitor resource usage with `kubectl top`
- Adjust resource requests/limits based on actual usage
- Use Spot Instances for non-critical workloads

### 2. Auto-Scaling
- Configure HPA for automatic scaling
- Use Cluster Autoscaler for node scaling
- Scale down during off-peak hours

### 3. Resource Cleanup
```bash
# Delete unused resources
kubectl delete deployment <name> -n resortslite
kubectl delete service <name> -n resortslite

# Clean up old images in ECR
aws ecr batch-delete-image --repository-name resortslite --image-ids imageTag=old-tag
```

---

## Support and Resources

### Documentation
- **Spring Boot:** https://spring.io/projects/spring-boot
- **AWS EKS:** https://docs.aws.amazon.com/eks/
- **Kubernetes:** https://kubernetes.io/docs/

### Useful Commands Cheat Sheet
```bash
# Kubernetes
kubectl get pods -n resortslite
kubectl logs -f deployment/resortslite -n resortslite
kubectl describe pod <pod-name> -n resortslite
kubectl exec -it <pod-name> -n resortslite -- /bin/sh

# Docker
docker ps
docker logs <container-id>
docker exec -it <container-id> /bin/sh

# AWS
aws eks list-clusters
aws ecr describe-repositories
aws sts get-caller-identity
```

---

## Conclusion

This deployment guide provides comprehensive instructions for deploying the ResortsLite Spring Boot application to AWS EKS. Follow the steps carefully, and refer to the troubleshooting section for common issues. For production deployments, ensure all security considerations are addressed and monitoring is properly configured.

For questions or issues, please contact the development team or refer to the official documentation links provided above.
