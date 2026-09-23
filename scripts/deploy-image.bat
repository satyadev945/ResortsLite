@echo off
setlocal enabledelayedexpansion

:: =============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: =============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "K8S_DIR=kubernetes"

echo ==============================================
echo   ResortsLite - Deploy to AWS EKS
echo ==============================================

:: ---- AWS / EKS configuration ----
set /p "AWS_REGION=Enter AWS region [us-east-1]: "
if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

set /p "CLUSTER_NAME=Enter EKS cluster name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS cluster name is required.
    exit /b 1
)

set /p "IMAGE_URI=Enter full Docker image URI: "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

echo.
echo --- Application Environment Variables ---
echo Press Enter to accept the default value shown in brackets.

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

set /p "REDIS_PASSWORD=Enter REDIS_PASSWORD (leave blank if none): "

set /p "APP_PAYMENT_ENDPOINT=Enter APP_PAYMENT_ENDPOINT [http://payment-svc:9090/charge]: "
if "!APP_PAYMENT_ENDPOINT!"=="" set "APP_PAYMENT_ENDPOINT=http://payment-svc:9090/charge"

set /p "APP_INVENTORY_ENDPOINT=Enter APP_INVENTORY_ENDPOINT [http://inventory-svc:8081/rooms]: "
if "!APP_INVENTORY_ENDPOINT!"=="" set "APP_INVENTORY_ENDPOINT=http://inventory-svc:8081/rooms"

set /p "APP_NOTIFICATION_ENDPOINT=Enter APP_NOTIFICATION_ENDPOINT [http://notify-svc:7070/send]: "
if "!APP_NOTIFICATION_ENDPOINT!"=="" set "APP_NOTIFICATION_ENDPOINT=http://notify-svc:7070/send"

set /p "REPORT_BASE_PATH=Enter REPORT_BASE_PATH [/var/reports]: "
if "!REPORT_BASE_PATH!"=="" set "REPORT_BASE_PATH=/var/reports"

set /p "BACKUP_PATH=Enter BACKUP_PATH [/var/backups/nightly]: "
if "!BACKUP_PATH!"=="" set "BACKUP_PATH=/var/backups/nightly"

set /p "BOOKING_CACHE_TTL_SECONDS=Enter BOOKING_CACHE_TTL_SECONDS [3600]: "
if "!BOOKING_CACHE_TTL_SECONDS!"=="" set "BOOKING_CACHE_TTL_SECONDS=3600"

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

:: ---- Create temp directory and copy manifests ----
set "TMP_DIR=%TEMP%\resortslite-deploy-%RANDOM%"
mkdir "!TMP_DIR!"
xcopy /E /I /Q "!K8S_DIR!" "!TMP_DIR!" >nul

set "DEPLOYMENT_YAML=!TMP_DIR!\deployment.yaml"

:: ---- Substitute placeholders using PowerShell ----
echo.
echo Updating Kubernetes manifests with deployment values...

powershell -NoProfile -Command ^
  "(Get-Content '!DEPLOYMENT_YAML!') ^
   -replace '{{IMAGE_URI}}','!IMAGE_URI!' ^
   -replace '{{SPRING_DATASOURCE_URL}}','!SPRING_DATASOURCE_URL!' ^
   -replace '{{SPRING_DATASOURCE_USERNAME}}','!SPRING_DATASOURCE_USERNAME!' ^
   -replace '{{SPRING_DATASOURCE_PASSWORD}}','!SPRING_DATASOURCE_PASSWORD!' ^
   -replace '{{REDIS_HOST}}','!REDIS_HOST!' ^
   -replace '{{REDIS_PORT}}','!REDIS_PORT!' ^
   -replace '{{REDIS_PASSWORD}}','!REDIS_PASSWORD!' ^
   -replace '{{APP_PAYMENT_ENDPOINT}}','!APP_PAYMENT_ENDPOINT!' ^
   -replace '{{APP_INVENTORY_ENDPOINT}}','!APP_INVENTORY_ENDPOINT!' ^
   -replace '{{APP_NOTIFICATION_ENDPOINT}}','!APP_NOTIFICATION_ENDPOINT!' ^
   -replace '{{REPORT_BASE_PATH}}','!REPORT_BASE_PATH!' ^
   -replace '{{BACKUP_PATH}}','!BACKUP_PATH!' ^
   -replace '{{BOOKING_CACHE_TTL_SECONDS}}','!BOOKING_CACHE_TTL_SECONDS!' ^
   | Set-Content '!DEPLOYMENT_YAML!'"

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
kubectl apply -f "!DEPLOYMENT_YAML!"
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

:: ---- Verify ----
echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ==============================================
echo   Deployment Complete!
echo   Health Check: http://<INGRESS_HOST>/actuator/health
echo ==============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

:: Cleanup
rmdir /S /Q "!TMP_DIR!" >nul 2>&1

endlocal
