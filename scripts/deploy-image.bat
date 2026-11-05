@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: Usage: scripts\deploy-image.bat
:: ============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "K8S_DIR=kubernetes"

echo ==============================================
echo   ResortsLite - AWS EKS Deployment Script
echo ==============================================
echo.

:: ---- Prompt for AWS / EKS details ----
set /p "AWS_REGION=Enter AWS Region (e.g. us-east-1): "
if "!AWS_REGION!"=="" (
    echo ERROR: AWS Region is required.
    exit /b 1
)

set /p "CLUSTER_NAME=Enter EKS Cluster Name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS Cluster Name is required.
    exit /b 1
)

set /p "IMAGE_URI=Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

echo.
echo ---- Application Environment Variables ----
echo Press Enter to skip any variable (default value will be used).
echo.

set /p "SPRING_DATASOURCE_URL=Enter SPRING_DATASOURCE_URL [jdbc:oracle:thin:@localhost:1521:ORCL]: "
if "!SPRING_DATASOURCE_URL!"=="" set "SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL"

set /p "SPRING_DATASOURCE_USERNAME=Enter SPRING_DATASOURCE_USERNAME [admin]: "
if "!SPRING_DATASOURCE_USERNAME!"=="" set "SPRING_DATASOURCE_USERNAME=admin"

set /p "SPRING_DATASOURCE_PASSWORD=Enter SPRING_DATASOURCE_PASSWORD: "
if "!SPRING_DATASOURCE_PASSWORD!"=="" set "SPRING_DATASOURCE_PASSWORD=changeme"

set /p "REDIS_HOST=Enter REDIS_HOST (ElastiCache endpoint) [localhost]: "
if "!REDIS_HOST!"=="" set "REDIS_HOST=localhost"

set /p "REDIS_PORT=Enter REDIS_PORT [6379]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"

set /p "REDIS_SSL=Enter REDIS_SSL (true/false) [false]: "
if "!REDIS_SSL!"=="" set "REDIS_SSL=false"

set /p "BOOKING_CACHE_TTL_SECONDS=Enter BOOKING_CACHE_TTL_SECONDS [3600]: "
if "!BOOKING_CACHE_TTL_SECONDS!"=="" set "BOOKING_CACHE_TTL_SECONDS=3600"

set /p "PAYMENT_SERVICE_URL=Enter PAYMENT_SERVICE_URL [http://payment-service:9090/payments/charge]: "
if "!PAYMENT_SERVICE_URL!"=="" set "PAYMENT_SERVICE_URL=http://payment-service:9090/payments/charge"

echo.
echo ---- Configuring kubectl for EKS ----
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to configure kubectl for EKS.
    exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to EKS cluster.
    exit /b 1
)

echo.
echo ---- Updating Kubernetes manifests ----

:: Use PowerShell to replace placeholders in deployment.yaml
powershell -NoProfile -Command ^
  "(Get-Content '!K8S_DIR!\deployment.yaml') ^
   -replace '{{IMAGE_URI}}','!IMAGE_URI!' ^
   -replace '{{SPRING_DATASOURCE_URL}}','!SPRING_DATASOURCE_URL!' ^
   -replace '{{SPRING_DATASOURCE_USERNAME}}','!SPRING_DATASOURCE_USERNAME!' ^
   -replace '{{SPRING_DATASOURCE_PASSWORD}}','!SPRING_DATASOURCE_PASSWORD!' ^
   -replace '{{REDIS_HOST}}','!REDIS_HOST!' ^
   -replace '{{REDIS_PORT}}','!REDIS_PORT!' ^
   -replace '{{REDIS_SSL}}','!REDIS_SSL!' ^
   -replace '{{BOOKING_CACHE_TTL_SECONDS}}','!BOOKING_CACHE_TTL_SECONDS!' ^
   -replace '{{PAYMENT_SERVICE_URL}}','!PAYMENT_SERVICE_URL!' ^
   | Set-Content '!K8S_DIR!\deployment.yaml'"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to update deployment.yaml.
    exit /b 1
)

echo.
echo ---- Applying Kubernetes manifests ----
kubectl apply -f "!K8S_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace.yaml & exit /b 1 )

kubectl apply -f "!K8S_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment.yaml & exit /b 1 )

kubectl apply -f "!K8S_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service.yaml & exit /b 1 )

kubectl apply -f "!K8S_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress.yaml & exit /b 1 )

echo.
echo ---- Waiting for deployment rollout ----
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed or timed out.
    echo To rollback: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

echo.
echo ---- Verifying deployed resources ----
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ---- Application Access URL ----
for /f "delims=" %%h in ('kubectl get ingress !APP_NAME!-ingress -n !NAMESPACE! -o jsonpath^="{.status.loadBalancer.ingress[0].hostname}" 2^>nul') do set "INGRESS_HOST=%%h"
if "!INGRESS_HOST!"=="" set "INGRESS_HOST=pending"
echo Ingress hostname: !INGRESS_HOST!
echo Application URL : http://!INGRESS_HOST!
echo.
echo NOTE: If hostname shows 'pending', wait a few minutes for the AWS ALB to provision.
echo.
echo ---- Rollback Instructions ----
echo To rollback to the previous version, run:
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
echo.
echo ==============================================
echo   Deployment Complete!
echo ==============================================

endlocal
exit /b 0
