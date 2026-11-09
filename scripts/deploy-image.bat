@echo off
setlocal enabledelayedexpansion

:: =============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: =============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"

echo ============================================
echo   ResortsLite - Deploy to AWS EKS
echo ============================================

:: Prompt for AWS region
set /p AWS_REGION="Enter AWS Region [us-east-1]: "
if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

:: Prompt for EKS cluster name
set /p CLUSTER_NAME="Enter EKS Cluster Name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS Cluster Name is required.
    exit /b 1
)

:: Prompt for Docker image URI
set /p IMAGE_URI="Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

:: Prompt for application environment variables
echo.
echo --- Application Configuration (press Enter to skip optional values) ---

set /p SPRING_DATASOURCE_URL="Enter SPRING_DATASOURCE_URL (Oracle JDBC URL): "
if "!SPRING_DATASOURCE_URL!"=="" set "SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL"

set /p SPRING_DATASOURCE_USERNAME="Enter SPRING_DATASOURCE_USERNAME [admin]: "
if "!SPRING_DATASOURCE_USERNAME!"=="" set "SPRING_DATASOURCE_USERNAME=admin"

set /p SPRING_DATASOURCE_PASSWORD="Enter SPRING_DATASOURCE_PASSWORD: "
if "!SPRING_DATASOURCE_PASSWORD!"=="" set "SPRING_DATASOURCE_PASSWORD=changeme"

set /p REDIS_HOST="Enter REDIS_HOST (ElastiCache endpoint) [localhost]: "
if "!REDIS_HOST!"=="" set "REDIS_HOST=localhost"

set /p REDIS_PORT="Enter REDIS_PORT [6379]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"

set /p REDIS_PASSWORD="Enter REDIS_PASSWORD (leave blank if none): "

set /p REDIS_SSL="Enable REDIS_SSL? [false]: "
if "!REDIS_SSL!"=="" set "REDIS_SSL=false"

set /p PAYMENT_API_URL="Enter PAYMENT_API_URL: "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-service.payments.svc.cluster.local:9090/payments/charge"

echo.
echo Configuring kubectl for EKS cluster: !CLUSTER_NAME! in !AWS_REGION! ...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to configure kubectl.
    exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to EKS cluster.
    exit /b 1
)

echo.
echo Updating Kubernetes manifests with provided values...

:: Use PowerShell to replace placeholders (Windows-compatible sed alternative)
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{SPRING_DATASOURCE_URL}}', '!SPRING_DATASOURCE_URL!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{SPRING_DATASOURCE_USERNAME}}', '!SPRING_DATASOURCE_USERNAME!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{SPRING_DATASOURCE_PASSWORD}}', '!SPRING_DATASOURCE_PASSWORD!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{REDIS_HOST}}', '!REDIS_HOST!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{REDIS_PORT}}', '!REDIS_PORT!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{REDIS_PASSWORD}}', '!REDIS_PASSWORD!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{REDIS_SSL}}', '!REDIS_SSL!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{PAYMENT_API_URL}}', '!PAYMENT_API_URL!' | Set-Content kubernetes\deployment.yaml"

echo.
echo Applying Kubernetes manifests...

echo   [1/4] Applying namespace...
kubectl apply -f kubernetes\namespace.yaml
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to apply namespace.
    exit /b 1
)

echo   [2/4] Applying deployment...
kubectl apply -f kubernetes\deployment.yaml
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to apply deployment.
    exit /b 1
)

echo   [3/4] Applying service...
kubectl apply -f kubernetes\service.yaml
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to apply service.
    exit /b 1
)

echo   [4/4] Applying ingress...
kubectl apply -f kubernetes\ingress.yaml
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to apply ingress.
    exit /b 1
)

echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed.
    echo Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ============================================
echo   Deployment Complete!
echo   Namespace: !NAMESPACE!
echo ============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

endlocal
exit /b 0
