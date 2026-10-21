# ResortsLite - AWS EKS Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Local Development Setup](#local-development-setup)
4. [Building Docker Image](#building-docker-image)
5. [AWS EKS Prerequisites](#aws-eks-prerequisites)
6. [EKS Cluster Setup](#eks-cluster-setup)
7. [Deploying to AWS EKS](#deploying-to-aws-eks)
8. [Configuration Management](#configuration-management)
9. [Monitoring and Health Checks](#monitoring-and-health-checks)
10. [Troubleshooting](#troubleshooting)
11. [Scaling and Management](#scaling-and-management)
12. [Security Considerations](#security-considerations)

---

## Overview

ResortsLite is a Spring Boot 2.7.x application built with Java 8, designed for containerized deployment on AWS EKS (Elastic Kubernetes Service). This guide provides comprehensive instructions for building, deploying, and managing the application in a production Kubernetes environment.

**Technology Stack:**
- Java 8 (OpenJDK)
- Spring Boot 2.7.18
- Maven 3.8+
- Oracle Database (JDBC)
- Redis (Session Store & Cache via Amazon ElastiCache)
- Spring Boot Actuator (Health Checks)

**Application Features:**
- RESTful API for resort booking management
- Externalized session storage with Redis
- Distributed caching with Redis
- Health check endpoints via Spring Boot Actuator
- Oracle database integration
- External payment service integration

---

## Prerequisites

### Required Software
- **Docker**: Version 20.10 or higher
- **Docker Compose**: Version 1.29 or higher (for local development)
- **AWS CLI**: Version 2.x
- **kubectl**: Version 1.24 or higher
- **eksctl**: Version 0.140 or higher (optional, for cluster creation)
- **Java**: JDK 8 or higher (for local development)
- **Maven**: Version 3.8 or higher (for local builds)

### AWS Account Requirements
- Active AWS account with appropriate permissions
- IAM user/role with EKS, ECR, EC2, and VPC permissions
- AWS credentials configured (`aws configure`)

### External Dependencies
- **Oracle Database**: Accessible from EKS cluster
- **Redis/ElastiCache**: For session storage and caching
- **Payment Service**: External payment API endpoint

---

## Local Development Setup

### 1. Clone the Repository
```bash
cd /path/to/Test_Comp1
```

### 2. Configure Application Properties
Edit `src/main/resources/application.properties` for local development:

```properties
# Local Database Configuration
spring.datasource.url=jdbc:oracle:thin:@localhost:1521:ORCL
spring.datasource.username=your_username
spring.datasource.password=your_password

# Local Redis Configuration
spring.redis.host=localhost
spring.redis.port=6379
```

### 3. Build the Application Locally
```bash
mvn clean package -DskipTests
```

### 4. Run with Docker Compose
```bash
# Start the application
docker-compose up -d

# View logs
docker-compose logs -f

# Stop the application
docker-compose down
```

The application will be available at: `http://localhost:8080`

Health check endpoint: `http://localhost:8080/actuator/health`

---

## Building Docker Image

### Using build-push.sh (Linux/macOS)

```bash
# Make script executable
chmod +x scripts/build-push.sh

# Run the script
./scripts/build-push.sh
```

### Using build-push.bat (Windows)

```cmd
scripts\build-push.bat
```

### Script Workflow
1. **Select Registry**: Choose between AWS ECR or Docker Hub
2. **Provide Credentials**: Enter registry-specific credentials
3. **Build Image**: Docker builds the multi-stage image
4. **Push Image**: Image is pushed to the selected registry

### Manual Docker Build
```bash
# Build the image
docker build -t resortslite:latest .

# Tag for ECR
docker tag resortslite:latest 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest

# Push to ECR
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin 123456789012.dkr.ecr.us-east-1.amazonaws.com
docker push 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
```

---

## AWS EKS Prerequisites

### 1. Install AWS CLI
```bash
# Linux/macOS
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip
sudo ./aws/install

# Verify installation
aws --version
```

### 2. Configure AWS Credentials
```bash
aws configure
# Enter: AWS Access Key ID, Secret Access Key, Region, Output format
```

### 3. Install kubectl
```bash
# Linux
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
chmod +x kubectl
sudo mv kubectl /usr/local/bin/

# macOS
brew install kubectl

# Verify installation
kubectl version --client
```

### 4. Install eksctl (Optional)
```bash
# Linux/macOS
curl --silent --location "https://github.com/weaveworks/eksctl/releases/latest/download/eksctl_$(uname -s)_amd64.tar.gz" | tar xz -C /tmp
sudo mv /tmp/eksctl /usr/local/bin

# Verify installation
eksctl version
```

### 5. Create ECR Repository
```bash
aws ecr create-repository \
    --repository-name resortslite \
    --region us-east-1
```

---

## EKS Cluster Setup

### Option 1: Create EKS Cluster with eksctl (Recommended)

```bash
eksctl create cluster \
    --name resortslite-cluster \
    --region us-east-1 \
    --nodegroup-name standard-workers \
    --node-type t3.medium \
    --nodes 2 \
    --nodes-min 1 \
    --nodes-max 4 \
    --managed
```

### Option 2: Use Existing EKS Cluster

```bash
# Configure kubectl to use existing cluster
aws eks update-kubeconfig --region us-east-1 --name your-cluster-name

# Verify connection
kubectl cluster-info
kubectl get nodes
```

### Install AWS Load Balancer Controller

The AWS Load Balancer Controller is required for Ingress resources:

```bash
# Create IAM policy
curl -o iam_policy.json https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v2.4.7/docs/install/iam_policy.json

aws iam create-policy \
    --policy-name AWSLoadBalancerControllerIAMPolicy \
    --policy-document file://iam_policy.json

# Create IAM role and service account
eksctl create iamserviceaccount \
    --cluster=resortslite-cluster \
    --namespace=kube-system \
    --name=aws-load-balancer-controller \
    --attach-policy-arn=arn:aws:iam::ACCOUNT_ID:policy/AWSLoadBalancerControllerIAMPolicy \
    --approve

# Install the controller using Helm
helm repo add eks https://aws.github.io/eks-charts
helm repo update

helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
    -n kube-system \
    --set clusterName=resortslite-cluster \
    --set serviceAccount.create=false \
    --set serviceAccount.name=aws-load-balancer-controller
```

---

## Deploying to AWS EKS

### Using deploy-image.sh (Linux/macOS)

```bash
# Make script executable
chmod +x scripts/deploy-image.sh

# Run the deployment script
./scripts/deploy-image.sh
```

### Using deploy-image.bat (Windows)

```cmd
scripts\deploy-image.bat
```

### Deployment Script Workflow

1. **AWS Configuration**: Enter AWS region and EKS cluster name
2. **Image URI**: Provide the full Docker image URI from ECR
3. **Environment Variables**: Configure database, Redis, and external services
4. **kubectl Configuration**: Script configures kubectl for your EKS cluster
5. **Manifest Updates**: Replaces placeholders with actual values
6. **Resource Creation**: Applies namespace, deployment, service, and ingress
7. **Rollout Verification**: Waits for deployment to complete

### Manual Deployment Steps

```bash
# 1. Configure kubectl
aws eks update-kubeconfig --region us-east-1 --name resortslite-cluster

# 2. Update deployment.yaml with your image URI
sed -i 's|{{IMAGE_URI}}|123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest|g' kubernetes/deployment.yaml

# 3. Update environment variables in deployment.yaml
# Edit kubernetes/deployment.yaml and replace {{PLACEHOLDER}} values

# 4. Apply Kubernetes manifests
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

# 5. Verify deployment
kubectl rollout status deployment/resortslite -n resortslite
kubectl get pods -n resortslite
```

---

## Configuration Management

### Environment Variables

The application requires the following environment variables:

#### Database Configuration
```yaml
SPRING_DATASOURCE_URL: jdbc:oracle:thin:@db-host:1521:ORCL
SPRING_DATASOURCE_USERNAME: admin
SPRING_DATASOURCE_PASSWORD: your-secure-password
```

#### Redis Configuration (Amazon ElastiCache)
```yaml
REDIS_HOST: your-elasticache-endpoint.cache.amazonaws.com
REDIS_PORT: 6379
REDIS_PASSWORD: your-redis-password
REDIS_SSL: true
```

#### External Services
```yaml
PAYMENT_API_URL: http://payment-service:9090/payments/charge
```

### Using Kubernetes Secrets

For sensitive data, use Kubernetes Secrets instead of plain environment variables:

```bash
# Create secret for database credentials
kubectl create secret generic db-credentials \
    --from-literal=username=admin \
    --from-literal=password=your-secure-password \
    -n resortslite

# Create secret for Redis credentials
kubectl create secret generic redis-credentials \
    --from-literal=password=your-redis-password \
    -n resortslite
```

Update `deployment.yaml` to use secrets:

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
kubectl create configmap app-config \
    --from-literal=PAYMENT_API_URL=http://payment-service:9090/payments/charge \
    -n resortslite
```

---

## Monitoring and Health Checks

### Health Check Endpoints

Spring Boot Actuator provides health check endpoints:

- **Liveness Probe**: `GET /actuator/health`
- **Readiness Probe**: `GET /actuator/health`

### Kubernetes Probes Configuration

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 90
  periodSeconds: 30
  timeoutSeconds: 10
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 60
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3
```

### Viewing Logs

```bash
# View logs for all pods
kubectl logs -f deployment/resortslite -n resortslite

# View logs for specific pod
kubectl logs -f <pod-name> -n resortslite

# View logs with timestamps
kubectl logs --timestamps -f deployment/resortslite -n resortslite

# View previous container logs (if pod crashed)
kubectl logs --previous <pod-name> -n resortslite
```

### Monitoring with kubectl

```bash
# Get pod status
kubectl get pods -n resortslite

# Describe pod (detailed information)
kubectl describe pod <pod-name> -n resortslite

# Get resource usage
kubectl top pods -n resortslite
kubectl top nodes

# Get events
kubectl get events -n resortslite --sort-by='.lastTimestamp'
```

---

## Troubleshooting

### Common Issues and Solutions

#### 1. Pod Not Starting (CrashLoopBackOff)

**Symptoms**: Pod status shows `CrashLoopBackOff` or `Error`

**Diagnosis**:
```bash
kubectl describe pod <pod-name> -n resortslite
kubectl logs <pod-name> -n resortslite
kubectl logs --previous <pod-name> -n resortslite
```

**Common Causes**:
- Database connection failure
- Redis connection failure
- Missing environment variables
- JVM memory issues

**Solutions**:
- Verify database connectivity from EKS cluster
- Check Redis/ElastiCache endpoint and credentials
- Ensure all required environment variables are set
- Adjust JVM memory settings in deployment.yaml

#### 2. Service Not Accessible

**Symptoms**: Cannot access application via LoadBalancer or Ingress

**Diagnosis**:
```bash
kubectl get svc -n resortslite
kubectl get ingress -n resortslite
kubectl describe ingress resortslite-ingress -n resortslite
```

**Solutions**:
- Verify AWS Load Balancer Controller is installed
- Check security groups allow traffic on port 80/443
- Verify ingress annotations are correct
- Check ALB provisioning status in AWS Console

#### 3. Database Connection Issues

**Symptoms**: Application logs show database connection errors

**Solutions**:
- Verify database security group allows traffic from EKS worker nodes
- Check database endpoint is correct
- Verify credentials are correct
- Test connectivity from a pod:
  ```bash
  kubectl run -it --rm debug --image=busybox --restart=Never -n resortslite -- sh
  # Inside pod: telnet db-host 1521
  ```

#### 4. Redis Connection Issues

**Symptoms**: Session storage or caching not working

**Solutions**:
- Verify ElastiCache security group allows traffic from EKS
- Check Redis endpoint and port
- Verify SSL/TLS settings match ElastiCache configuration
- Test connectivity from a pod

#### 5. Image Pull Errors

**Symptoms**: Pod status shows `ImagePullBackOff` or `ErrImagePull`

**Diagnosis**:
```bash
kubectl describe pod <pod-name> -n resortslite
```

**Solutions**:
- Verify ECR repository exists and image is pushed
- Check IAM permissions for EKS worker nodes to pull from ECR
- Verify image URI is correct in deployment.yaml
- Ensure ECR repository is in the same region as EKS cluster

#### 6. Out of Memory Errors

**Symptoms**: Pods being killed, OOMKilled status

**Solutions**:
- Increase memory limits in deployment.yaml
- Adjust JVM heap size (JAVA_OPTS)
- Review application memory usage patterns
- Consider horizontal scaling instead of vertical

### Debugging Commands

```bash
# Execute shell in running pod
kubectl exec -it <pod-name> -n resortslite -- /bin/sh

# Port forward to local machine
kubectl port-forward deployment/resortslite 8080:8080 -n resortslite

# Check resource quotas
kubectl describe resourcequota -n resortslite

# Check network policies
kubectl get networkpolicies -n resortslite

# View all resources in namespace
kubectl get all -n resortslite
```

---

## Scaling and Management

### Horizontal Pod Autoscaling (HPA)

Create HPA based on CPU utilization:

```bash
kubectl autoscale deployment resortslite \
    --cpu-percent=70 \
    --min=2 \
    --max=10 \
    -n resortslite
```

Or create HPA manifest:

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

### Manual Scaling

```bash
# Scale to 5 replicas
kubectl scale deployment resortslite --replicas=5 -n resortslite

# Verify scaling
kubectl get pods -n resortslite
```

### Rolling Updates

```bash
# Update image
kubectl set image deployment/resortslite \
    resortslite=123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:v2.0 \
    -n resortslite

# Check rollout status
kubectl rollout status deployment/resortslite -n resortslite

# View rollout history
kubectl rollout history deployment/resortslite -n resortslite
```

### Rollback Deployment

```bash
# Rollback to previous version
kubectl rollout undo deployment/resortslite -n resortslite

# Rollback to specific revision
kubectl rollout undo deployment/resortslite --to-revision=2 -n resortslite

# Verify rollback
kubectl rollout status deployment/resortslite -n resortslite
```

### Resource Management

Update resource limits:

```yaml
resources:
  requests:
    cpu: "500m"
    memory: "1Gi"
  limits:
    cpu: "1000m"
    memory: "2Gi"
```

Apply changes:
```bash
kubectl apply -f kubernetes/deployment.yaml
```

---

## Security Considerations

### 1. Container Security

- **Non-root User**: Application runs as non-root user (appuser)
- **Read-only Root Filesystem**: Consider adding `readOnlyRootFilesystem: true`
- **Security Context**: Add security context to pod spec:

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 1000
  fsGroup: 1000
  capabilities:
    drop:
    - ALL
```

### 2. Network Security

- **Network Policies**: Restrict pod-to-pod communication
- **Security Groups**: Configure AWS security groups for EKS worker nodes
- **Private Subnets**: Deploy worker nodes in private subnets
- **VPC Endpoints**: Use VPC endpoints for AWS services (ECR, S3)

### 3. Secrets Management

- **AWS Secrets Manager**: Store sensitive data in AWS Secrets Manager
- **External Secrets Operator**: Sync secrets from AWS to Kubernetes
- **Encryption at Rest**: Enable encryption for Kubernetes secrets

### 4. Image Security

- **Vulnerability Scanning**: Scan images with AWS ECR image scanning
- **Image Signing**: Sign images with Docker Content Trust
- **Base Image Updates**: Regularly update base images for security patches

### 5. Access Control

- **RBAC**: Implement Role-Based Access Control
- **IAM Roles**: Use IAM roles for service accounts (IRSA)
- **Pod Security Standards**: Enforce pod security standards

### 6. Audit Logging

- **CloudWatch Logs**: Enable EKS control plane logging
- **Application Logs**: Stream application logs to CloudWatch
- **Audit Logs**: Enable Kubernetes audit logging

---

## Java-Specific Considerations

### JVM Memory Configuration

The application uses the following JVM settings optimized for containers:

```bash
JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
```

**Explanation**:
- `-Xmx512m`: Maximum heap size
- `-Xms256m`: Initial heap size
- `-XX:+UseContainerSupport`: Enable container awareness
- `-XX:MaxRAMPercentage=75.0`: Use 75% of container memory for heap

### Spring Boot Profiles

Use Spring profiles for environment-specific configuration:

```yaml
env:
- name: SPRING_PROFILES_ACTIVE
  value: "production"
```

### Graceful Shutdown

Spring Boot handles graceful shutdown automatically. Kubernetes sends SIGTERM signal, and Spring Boot completes in-flight requests before shutting down.

### Startup Time Optimization

- Use Spring Boot 2.7.x lazy initialization for faster startup
- Consider GraalVM native image for even faster startup (requires migration)
- Adjust `initialDelaySeconds` in probes based on actual startup time

---

## Additional Resources

### AWS Documentation
- [Amazon EKS User Guide](https://docs.aws.amazon.com/eks/latest/userguide/)
- [Amazon ECR User Guide](https://docs.aws.amazon.com/ecr/latest/userguide/)
- [AWS Load Balancer Controller](https://kubernetes-sigs.github.io/aws-load-balancer-controller/)

### Kubernetes Documentation
- [Kubernetes Documentation](https://kubernetes.io/docs/)
- [kubectl Cheat Sheet](https://kubernetes.io/docs/reference/kubectl/cheatsheet/)

### Spring Boot Documentation
- [Spring Boot Reference](https://docs.spring.io/spring-boot/docs/2.7.x/reference/html/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/2.7.x/reference/html/actuator.html)

---

## Support and Maintenance

### Regular Maintenance Tasks

1. **Update Dependencies**: Regularly update Spring Boot and dependencies
2. **Security Patches**: Apply security patches promptly
3. **Monitor Logs**: Review application and system logs regularly
4. **Backup Data**: Ensure database backups are configured
5. **Test Disaster Recovery**: Regularly test backup and recovery procedures

### Monitoring Recommendations

- Set up CloudWatch alarms for pod failures
- Monitor application metrics with Prometheus/Grafana
- Configure log aggregation with CloudWatch Logs or ELK stack
- Set up distributed tracing with AWS X-Ray or Jaeger

---

## Conclusion

This deployment guide provides comprehensive instructions for deploying ResortsLite to AWS EKS. Follow the steps carefully, and refer to the troubleshooting section for common issues. For production deployments, ensure all security considerations are addressed and monitoring is properly configured.

For questions or issues, consult the AWS and Kubernetes documentation or contact your DevOps team.
