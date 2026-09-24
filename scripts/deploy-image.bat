@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: ============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%.."

echo ============================================
echo   ResortsLite - Deploy to AWS EKS
echo ============================================
echo.

:: ---- AWS / EKS configuration ----
set /p "AWS_REGION=Enter AWS Region [us-east-1]: "
if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

set /p "CLUSTER_NAME=Enter EKS Cluster Name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS Cluster Name is required.
    exit /b 1
)

set /p "IMAGE_URI=Enter full Docker image URI: "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

echo.
echo ---- Application Environment Variables ----
echo Press Enter to use the default value shown in brackets.
echo.

set /p "REDIS_HOST=Enter REDIS_HOST [localhost]: "
if "!REDIS_HOST!"=="" set "REDIS_HOST=localhost"

set /p "REDIS_PORT=Enter REDIS_PORT [6379]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"

set /p "SPRING_DATASOURCE_URL=Enter SPRING_DATASOURCE_URL: "
if "!SPRING_DATASOURCE_URL!"=="" set "SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL"

set /p "SPRING_DATASOURCE_USERNAME=Enter SPRING_DATASOURCE_USERNAME [admin]: "
if "!SPRING_DATASOURCE_USERNAME!"=="" set "SPRING_DATASOURCE_USERNAME=admin"

set /p "SPRING_DATASOURCE_PASSWORD=Enter SPRING_DATASOURCE_PASSWORD: "
if "!SPRING_DATASOURCE_PASSWORD!"=="" set "SPRING_DATASOURCE_PASSWORD=changeme"

set /p "PAYMENT_API_URL=Enter PAYMENT_API_URL: "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-svc.payments.svc.cluster.local:9090/payments/charge"

set /p "APP_PAYMENT_ENDPOINT=Enter APP_PAYMENT_ENDPOINT: "
if "!APP_PAYMENT_ENDPOINT!"=="" set "APP_PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge"

set /p "APP_INVENTORY_ENDPOINT=Enter APP_INVENTORY_ENDPOINT: "
if "!APP_INVENTORY_ENDPOINT!"=="" set "APP_INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms"

set /p "APP_NOTIFICATION_ENDPOINT=Enter APP_NOTIFICATION_ENDPOINT: "
if "!APP_NOTIFICATION_ENDPOINT!"=="" set "APP_NOTIFICATION_ENDPOINT=http://notify.internal:7070/send"

set /p "REPORT_BASE_PATH=Enter REPORT_BASE_PATH [/var/reports]: "
if "!REPORT_BASE_PATH!"=="" set "REPORT_BASE_PATH=/var/reports"

:: ---- Configure kubectl ----
echo.
echo Configuring kubectl for EKS cluster: !CLUSTER_NAME! ...
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

:: ---- Patch deployment manifest using PowerShell ----
echo.
echo Updating Kubernetes manifests with provided values...

set "DEPLOY_YAML=%PROJECT_ROOT%\kubernetes\deployment.yaml"

powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '{{REDIS_HOST}}', '!REDIS_HOST!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '{{REDIS_PORT}}', '!REDIS_PORT!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '{{SPRING_DATASOURCE_URL}}', '!SPRING_DATASOURCE_URL!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '{{SPRING_DATASOURCE_USERNAME}}', '!SPRING_DATASOURCE_USERNAME!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '{{SPRING_DATASOURCE_PASSWORD}}', '!SPRING_DATASOURCE_PASSWORD!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '{{PAYMENT_API_URL}}', '!PAYMENT_API_URL!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '{{APP_PAYMENT_ENDPOINT}}', '!APP_PAYMENT_ENDPOINT!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '{{APP_INVENTORY_ENDPOINT}}', '!APP_INVENTORY_ENDPOINT!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '{{APP_NOTIFICATION_ENDPOINT}}', '!APP_NOTIFICATION_ENDPOINT!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '{{REPORT_BASE_PATH}}', '!REPORT_BASE_PATH!' | Set-Content '!DEPLOY_YAML!'"

:: ---- Apply manifests ----
echo.
echo Applying Kubernetes manifests...

echo   [1/4] Applying namespace...
kubectl apply -f "%PROJECT_ROOT%\kubernetes\namespace.yaml"
if !ERRORLEVEL! neq 0 (echo ERROR: Failed to apply namespace. & exit /b 1)

echo   [2/4] Applying deployment...
kubectl apply -f "%PROJECT_ROOT%\kubernetes\deployment.yaml"
if !ERRORLEVEL! neq 0 (echo ERROR: Failed to apply deployment. & exit /b 1)

echo   [3/4] Applying service...
kubectl apply -f "%PROJECT_ROOT%\kubernetes\service.yaml"
if !ERRORLEVEL! neq 0 (echo ERROR: Failed to apply service. & exit /b 1)

echo   [4/4] Applying ingress...
kubectl apply -f "%PROJECT_ROOT%\kubernetes\ingress.yaml"
if !ERRORLEVEL! neq 0 (echo ERROR: Failed to apply ingress. & exit /b 1)

:: ---- Wait for rollout ----
echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed.
    echo Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

:: ---- Verify ----
echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ============================================
echo   Deployment Complete!
echo   Health Check: http://^<INGRESS_HOST^>/actuator/health
echo ============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

endlocal
exit /b 0
