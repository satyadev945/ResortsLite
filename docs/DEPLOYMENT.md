# Deployment Guide for Test_Comp1

## Overview
Test_Comp1 is a Java 8 Spring Boot 2.7 application built with Maven and packaged as an executable JAR. This guide covers local Docker usage, image publishing, and Azure AKS deployment.

## Prerequisites
- Java 8 compatible runtime for local non-container execution
- Docker Desktop or Docker Engine
- Azure CLI authenticated to your subscription
- kubectl configured locally
- Access to an Azure AKS cluster
- Access to Azure Container Registry or Docker Hub

## Project Details
- Framework: Spring Boot 2.7.18
- Build tool: Maven
- Package type: JAR
- Application port: 8080
- Health endpoint: `/health`
- Runtime base image: `eclipse-temurin:8-jre`

## Local Docker Build
Build the container image from the repository root:

```bash
docker build -t test-comp1:latest .
```

Run locally:

```bash
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=docker \
  -e SPRING_DATASOURCE_URL='jdbc:h2:mem:resortdb;DB_CLOSE_DELAY=-1' \
  -e SPRING_DATASOURCE_USERNAME=sa \
  -e SPRING_DATASOURCE_PASSWORD= \
  test-comp1:latest
```

Verify health:

```bash
curl http://localhost:8080/health
```

## Docker Compose
Use docker-compose to start only the application container:

```bash
docker compose up -d
```

The compose file exposes port 8080 and mounts `./config` and `./data` for externalized configuration and generated files.

## Build and Push Scripts
### Linux/macOS
```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

### Windows
```bat
scripts\build-push.bat
```

The scripts:
- prompt for image tag
- sanitize image names and tags
- prompt for Azure ACR or Docker Hub
- authenticate to the selected registry
- build the Docker image from the repository root
- push the image to the selected registry

## Azure AKS Deployment
### 1. Prepare AKS Access
Ensure you are logged in:

```bash
az login
az account set --subscription <subscription-id>
```

### 2. Review Kubernetes Manifests
Files are located in `kubernetes/`:
- `namespace.yaml`
- `deployment.yaml`
- `service.yaml`
- `ingress.yaml`

The deployment manifest includes placeholders for:
- image URI
- datasource settings
- payment, inventory, and notification endpoints
- Redis connectivity values

### 3. Deploy with Script
Linux/macOS:

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

Windows:

```bat
scripts\deploy-image.bat
```

The deployment scripts will:
1. prompt for Azure resource group and AKS cluster name
2. prompt for the full image URI
3. prompt for application environment variables
4. configure kubectl using `az aks get-credentials`
5. apply namespace, deployment, service, and ingress manifests
6. wait for rollout completion
7. display deployed resources

## Manual Kubernetes Deployment
If you prefer manual deployment, replace placeholders in `kubernetes/deployment.yaml` and apply:

```bash
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml
kubectl rollout status deployment/test-comp1 -n test-comp1
```

## Configuration Management
Recommended environment variables:
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `PAYMENT_ENDPOINT`
- `INVENTORY_ENDPOINT`
- `NOTIFICATION_ENDPOINT`
- `REDIS_HOST`
- `REDIS_PORT`
- `REDIS_PASSWORD`
- `REDIS_SSL`
- `REPORT_BASE_PATH`
- `BACKUP_PATH`

For production, store secrets in Azure Key Vault or Kubernetes Secrets instead of plain manifest values.

## Health Checks and Monitoring
- Liveness probe: `GET /health`
- Readiness probe: `GET /health`
- Container JVM options are tuned for 512Mi–1Gi memory limits
- Consider adding Spring Boot Actuator in a future iteration for richer observability

## Troubleshooting
### Pod fails to start
- Check logs: `kubectl logs deployment/test-comp1 -n test-comp1`
- Verify image URI and registry access
- Confirm required environment variables are set

### Image pull errors
- Validate ACR or Docker Hub credentials
- If using ACR, ensure AKS has pull permissions

### Ingress not reachable
- Confirm Application Gateway Ingress Controller is installed
- Update the ingress host from `test-comp1.example.com` to your real DNS name
- Check ingress events: `kubectl describe ingress test-comp1-ingress -n test-comp1`

### Readiness or liveness failures
- Verify the application responds on port 8080
- Test `/health` from inside the cluster
- Increase initial delay if startup is slower in your environment

## Scaling and Rollback
Scale deployment:

```bash
kubectl scale deployment/test-comp1 --replicas=3 -n test-comp1
```

Rollback deployment:

```bash
kubectl rollout undo deployment/test-comp1 -n test-comp1
```

## Security Considerations
- Run as a non-root user in the container
- Avoid embedding secrets in source control
- Use HTTPS endpoints for external integrations
- Restrict ingress hostnames and TLS-configure the ingress for production
- Scan the image before promotion to production

## Java-Specific Notes
- Builder image uses Maven with Eclipse Temurin JDK 8
- Runtime image uses the explicit base image `eclipse-temurin:8-jre`
- JVM options include container-aware memory settings
- The application is packaged from `target/*.jar`
