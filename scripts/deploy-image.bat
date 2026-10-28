@echo off
setlocal enabledelayedexpansion

:: =============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: Usage: scripts\deploy-image.bat
:: Run from the repository root directory
:: =============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "K8S_DIR=kubernetes"

echo ==============================================
echo   ResortsLite -- AWS EKS Deployment
echo ==============================================

:: ---- Collect deployment inputs ----
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

set /p "IMAGE_URI=Enter full Docker image URI: "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

echo.
echo --- Application Environment Variables ---
echo Press Enter to skip any variable.

set /p "REDIS_HOST_VAL=Enter REDIS_HOST (ElastiCache endpoint): "
set /p "REDIS_PORT_VAL=Enter REDIS_PORT [6379]: "
if "!REDIS_PORT_VAL!"=="" set "REDIS_PORT_VAL=6379"

set /p "SPRING_DATASOURCE_URL_VAL=Enter SPRING_DATASOURCE_URL (Oracle JDBC URL): "
set /p "SPRING_DATASOURCE_USERNAME_VAL=Enter SPRING_DATASOURCE_USERNAME: "
set /p "SPRING_DATASOURCE_PASSWORD_VAL=Enter SPRING_DATASOURCE_PASSWORD: "

set /p "PAYMENT_API_URL_VAL=Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: "
if "!PAYMENT_API_URL_VAL!"=="" set "PAYMENT_API_URL_VAL=http://payment-service:9090/payments/charge"

set /p "APP_PAYMENT_ENDPOINT_VAL=Enter APP_PAYMENT_ENDPOINT [http://payment-svc.internal:9090/charge]: "
if "!APP_PAYMENT_ENDPOINT_VAL!"=="" set "APP_PAYMENT_ENDPOINT_VAL=http://payment-svc.internal:9090/charge"

set /p "APP_INVENTORY_ENDPOINT_VAL=Enter APP_INVENTORY_ENDPOINT [http://inventory-svc.internal:8081/rooms]: "
if "!APP_INVENTORY_ENDPOINT_VAL!"=="" set "APP_INVENTORY_ENDPOINT_VAL=http://inventory-svc.internal:8081/rooms"

set /p "APP_NOTIFICATION_ENDPOINT_VAL=Enter APP_NOTIFICATION_ENDPOINT [http://notify.internal:7070/send]: "
if "!APP_NOTIFICATION_ENDPOINT_VAL!"=="" set "APP_NOTIFICATION_ENDPOINT_VAL=http://notify.internal:7070/send"

set /p "REPORT_BASE_PATH_VAL=Enter REPORT_BASE_PATH [/var/reports]: "
if "!REPORT_BASE_PATH_VAL!"=="" set "REPORT_BASE_PATH_VAL=/var/reports"

:: ---- Configure kubectl for EKS ----
echo.
echo Configuring kubectl for EKS cluster: !CLUSTER_NAME! in !AWS_REGION!...
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

:: ---- Create temp directory for manifest substitution ----
set "TMP_DIR=%TEMP%\resortslite-deploy-%RANDOM%"
mkdir "!TMP_DIR!"
xcopy /E /I /Q "!K8S_DIR!" "!TMP_DIR!" >nul

:: ---- Substitute placeholders using PowerShell ----
echo.
echo Updating Kubernetes manifests with deployment values...

powershell -NoProfile -Command ^
  "$content = Get-Content '!TMP_DIR!\deployment.yaml' -Raw;" ^
  "$content = $content -replace '{{IMAGE_URI}}','!IMAGE_URI!';" ^
  "$content = $content -replace '{{REDIS_HOST}}','!REDIS_HOST_VAL!';" ^
  "$content = $content -replace '{{REDIS_PORT}}','!REDIS_PORT_VAL!';" ^
  "$content = $content -replace '{{SPRING_DATASOURCE_URL}}','!SPRING_DATASOURCE_URL_VAL!';" ^
  "$content = $content -replace '{{SPRING_DATASOURCE_USERNAME}}','!SPRING_DATASOURCE_USERNAME_VAL!';" ^
  "$content = $content -replace '{{SPRING_DATASOURCE_PASSWORD}}','!SPRING_DATASOURCE_PASSWORD_VAL!';" ^
  "$content = $content -replace '{{PAYMENT_API_URL}}','!PAYMENT_API_URL_VAL!';" ^
  "$content = $content -replace '{{APP_PAYMENT_ENDPOINT}}','!APP_PAYMENT_ENDPOINT_VAL!';" ^
  "$content = $content -replace '{{APP_INVENTORY_ENDPOINT}}','!APP_INVENTORY_ENDPOINT_VAL!';" ^
  "$content = $content -replace '{{APP_NOTIFICATION_ENDPOINT}}','!APP_NOTIFICATION_ENDPOINT_VAL!';" ^
  "$content = $content -replace '{{REPORT_BASE_PATH}}','!REPORT_BASE_PATH_VAL!';" ^
  "Set-Content '!TMP_DIR!\deployment.yaml' -Value $content"

if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to update deployment manifest.
    exit /b 1
)

:: ---- Apply manifests ----
echo.
echo Applying Kubernetes manifests...

echo   [1/4] Applying namespace...
kubectl apply -f "!TMP_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo   [2/4] Applying deployment...
kubectl apply -f "!TMP_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo   [3/4] Applying service...
kubectl apply -f "!TMP_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo   [4/4] Applying ingress...
kubectl apply -f "!TMP_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

:: ---- Wait for rollout ----
echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed.
    echo Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

:: ---- Verify resources ----
echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ==============================================
echo   Deployment Complete!
echo   Health Check: http://^<INGRESS_HOST^>/actuator/health
echo ==============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

:: Cleanup
rmdir /S /Q "!TMP_DIR!" >nul 2>&1

endlocal
