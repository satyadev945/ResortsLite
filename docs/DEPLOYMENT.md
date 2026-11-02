# ResortsLite Deployment Guide

## Prerequisites
- Docker 24+
- Azure CLI 2.50+
- kubectl compatible with your AKS cluster
- Access to an Azure subscription and AKS cluster
- Network access to your target container registry

## Application Overview
- Framework: Spring Boot 2.7.18
- Java version: 8
- Build tool: Maven
- Packaging: Executable JAR
- Application port: 8080
- Health endpoint: `/actuator/health`

## Local Development with Docker Compose
1. Review `docker-compose.yml` and adjust environment variables as needed.
2. Start the application:
   ```bash
   docker compose up --build
   ```
3. Verify the application:
   - App: `http://localhost:8080`
   - Health: `http://localhost:8080/actuator/health`
   - H2 console: `http://localhost:8080/h2-console`
4. Stop the stack:
   ```bash
   docker compose down
   ```

## Docker Build and Push
### Linux/macOS
```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```
The script prompts for:
- Image tag
- Registry type (Azure ACR or Docker Hub)
- Registry credentials

### Windows
```bat
scripts\build-push.bat
```
The batch script performs the same workflow for Windows users.

## Azure AKS Deployment
### Prepare Azure Access
1. Sign in:
   ```bash
   az login
   ```
2. Confirm the active subscription:
   ```bash
   az account show
   ```
3. Ensure the AKS cluster and ingress controller are available.

### Deploy from Linux/macOS
```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```
Provide:
- Azure resource group
- AKS cluster name
- Full image URI including tag
- Optional environment variable values for datasource and downstream services

### Deploy from Windows
```bat
scripts\deploy-image.bat
```

## Kubernetes Manifest Details
- `kubernetes/namespace.yaml`: Creates the `resortslite` namespace.
- `kubernetes/deployment.yaml`: Runs 2 replicas with JVM tuning, resource requests/limits, and actuator probes.
- `kubernetes/service.yaml`: Exposes the application internally on port 80.
- `kubernetes/ingress.yaml`: Routes traffic through Azure Application Gateway Ingress Controller.

## Configuration Management
Set these values during deployment or through your preferred secret/config management solution:
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `APP_PAYMENT_ENDPOINT`
- `APP_INVENTORY_ENDPOINT`
- `APP_NOTIFICATION_ENDPOINT`
- `CACHE_PROVIDER`

For production, prefer Kubernetes Secrets, Azure Key Vault, or external secret operators instead of plain-text manifest values.

## Operational Guidance
- Scale replicas:
  ```bash
  kubectl scale deployment/resortslite --replicas=3 -n resortslite
  ```
- Check rollout status:
  ```bash
  kubectl rollout status deployment/resortslite -n resortslite
  ```
- Roll back:
  ```bash
  kubectl rollout undo deployment/resortslite -n resortslite
  ```

## Troubleshooting
### Pod startup failures
- Inspect logs:
  ```bash
  kubectl logs deployment/resortslite -n resortslite
  ```
- Describe pods:
  ```bash
  kubectl describe pods -n resortslite
  ```

### Service or ingress issues
- Verify service and ingress:
  ```bash
  kubectl get svc,ingress -n resortslite
  ```
- Confirm ingress controller annotations and DNS mapping.

### Registry pull failures
- Validate image URI and tag.
- Confirm AKS has permission to pull from ACR or the registry secret is configured.

## Security Considerations
- Run containers as a non-root user.
- Keep the runtime image minimal and avoid installing extra packages.
- Externalize credentials and service endpoints.
- Restrict ingress hosts and TLS-configure the public endpoint.
- Monitor `/actuator/health` and application logs.

## Java-Specific Notes
- Runtime base image uses the explicit image `amazoncorretto:8`.
- Builder image uses `maven:3.9.4-eclipse-temurin-8`.
- JVM options are tuned for container memory limits:
  `-Xms256m -Xmx512m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`
- Spring profile `docker` is enabled by default in containerized environments.
