@echo off
setlocal enabledelayedexpansion

:: =============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: =============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "K8S_DIR=kubernetes"
set "TEMP_K8S_DIR=%TEMP%\k8s-deploy-resortslite"

echo ==============================================
echo   ResortsLite - AWS EKS Deployment Script
echo ==============================================
echo.

:: ── AWS / EKS configuration ───────────────────────────────────
set /p "AWS_REGION=Enter AWS Region [us-east-1]: "
if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

set /p "CLUSTER_NAME=Enter EKS Cluster Name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS Cluster Name is required.
    exit /b 1
)

:: ── Docker image URI ──────────────────────────────────────────
set /p "IMAGE_URI=Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

:: ── Application environment variables ────────────────────────
echo.
echo --- Application Environment Variables ---
echo Press Enter to use the default value shown in brackets.
echo.

set /p "SPRING_DATASOURCE_URL=Enter SPRING_DATASOURCE_URL [jdbc:oracle:thin:@^<DB_HOST^>:1521:^<DB_SID^>]: "
if "!SPRING_DATASOURCE_URL!"=="" set "SPRING_DATASOURCE_URL=jdbc:oracle:thin:@<DB_HOST>:1521:<DB_SID>"

set /p "SPRING_DATASOURCE_USERNAME=Enter SPRING_DATASOURCE_USERNAME [admin]: "
if "!SPRING_DATASOURCE_USERNAME!"=="" set "SPRING_DATASOURCE_USERNAME=admin"

set /p "SPRING_DATASOURCE_PASSWORD=Enter SPRING_DATASOURCE_PASSWORD: "
if "!SPRING_DATASOURCE_PASSWORD!"=="" set "SPRING_DATASOURCE_PASSWORD=changeme"

set /p "REDIS_HOST=Enter REDIS_HOST (ElastiCache endpoint) [localhost]: "
if "!REDIS_HOST!"=="" set "REDIS_HOST=localhost"

set /p "REDIS_PORT=Enter REDIS_PORT [6379]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"

set /p "REDIS_PASSWORD=Enter REDIS_PASSWORD (leave blank if none): "

set /p "REDIS_SSL=Enter REDIS_SSL [false]: "
if "!REDIS_SSL!"=="" set "REDIS_SSL=false"

set /p "BOOKING_CACHE_TTL_SECONDS=Enter BOOKING_CACHE_TTL_SECONDS [3600]: "
if "!BOOKING_CACHE_TTL_SECONDS!"=="" set "BOOKING_CACHE_TTL_SECONDS=3600"

set /p "PAYMENT_API_URL=Enter PAYMENT_API_URL [http://payment-svc.default.svc.cluster.local:9090/payments/charge]: "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-svc.default.svc.cluster.local:9090/payments/charge"

set /p "APP_INVENTORY_ENDPOINT=Enter APP_INVENTORY_ENDPOINT [http://inventory-svc.internal:8081/rooms]: "
if "!APP_INVENTORY_ENDPOINT!"=="" set "APP_INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms"

set /p "APP_NOTIFICATION_ENDPOINT=Enter APP_NOTIFICATION_ENDPOINT [http://notify.internal:7070/send]: "
if "!APP_NOTIFICATION_ENDPOINT!"=="" set "APP_NOTIFICATION_ENDPOINT=http://notify.internal:7070/send"

set /p "REPORTS_BASE_PATH=Enter REPORTS_BASE_PATH [/var/reports]: "
if "!REPORTS_BASE_PATH!"=="" set "REPORTS_BASE_PATH=/var/reports"

set /p "BACKUP_PATH=Enter BACKUP_PATH [/var/backups/resort/nightly]: "
if "!BACKUP_PATH!"=="" set "BACKUP_PATH=/var/backups/resort/nightly"

:: ── Configure kubectl for EKS ─────────────────────────────────
echo.
echo Configuring kubectl for EKS cluster '!CLUSTER_NAME!' in '!AWS_REGION!'...
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

:: ── Copy manifests to temp directory ─────────────────────────
echo.
echo Preparing Kubernetes manifests...
if exist "!TEMP_K8S_DIR!" rmdir /s /q "!TEMP_K8S_DIR!"
xcopy /e /i /q "!K8S_DIR!" "!TEMP_K8S_DIR!" >nul
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to copy Kubernetes manifests.
    exit /b 1
)

:: ── Substitute placeholders using PowerShell ─────────────────
set "DEPLOY_FILE=!TEMP_K8S_DIR!\deployment.yaml"

powershell -NoProfile -Command ^
  "(Get-Content '!DEPLOY_FILE!') ^
   -replace '{{IMAGE_URI}}','!IMAGE_URI!' ^
   -replace '{{SPRING_DATASOURCE_URL}}','!SPRING_DATASOURCE_URL!' ^
   -replace '{{SPRING_DATASOURCE_USERNAME}}','!SPRING_DATASOURCE_USERNAME!' ^
   -replace '{{SPRING_DATASOURCE_PASSWORD}}','!SPRING_DATASOURCE_PASSWORD!' ^
   -replace '{{REDIS_HOST}}','!REDIS_HOST!' ^
   -replace '{{REDIS_PORT}}','!REDIS_PORT!' ^
   -replace '{{REDIS_PASSWORD}}','!REDIS_PASSWORD!' ^
   -replace '{{REDIS_SSL}}','!REDIS_SSL!' ^
   -replace '{{BOOKING_CACHE_TTL_SECONDS}}','!BOOKING_CACHE_TTL_SECONDS!' ^
   -replace '{{PAYMENT_API_URL}}','!PAYMENT_API_URL!' ^
   -replace '{{APP_INVENTORY_ENDPOINT}}','!APP_INVENTORY_ENDPOINT!' ^
   -replace '{{APP_NOTIFICATION_ENDPOINT}}','!APP_NOTIFICATION_ENDPOINT!' ^
   -replace '{{REPORTS_BASE_PATH}}','!REPORTS_BASE_PATH!' ^
   -replace '{{BACKUP_PATH}}','!BACKUP_PATH!' ^
   | Set-Content '!DEPLOY_FILE!'"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to update deployment manifest.
    exit /b 1
)
echo Manifests updated.

:: ── Apply Kubernetes manifests ────────────────────────────────
echo.
echo Applying Kubernetes manifests...

echo [1/4] Applying namespace...
kubectl apply -f "!TEMP_K8S_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo [2/4] Applying deployment...
kubectl apply -f "!DEPLOY_FILE!"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo [3/4] Applying service...
kubectl apply -f "!TEMP_K8S_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo [4/4] Applying ingress...
kubectl apply -f "!TEMP_K8S_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

:: ── Wait for rollout ──────────────────────────────────────────
echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout timed out or failed.
    echo To rollback, run: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

:: ── Verify resources ──────────────────────────────────────────
echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

:: ── Display access URL ────────────────────────────────────────
echo.
for /f "delims=" %%h in ('kubectl get ingress resortslite-ingress -n !NAMESPACE! -o jsonpath^="{.status.loadBalancer.ingress[0].hostname}" 2^>nul') do set "INGRESS_HOST=%%h"
if "!INGRESS_HOST!"=="" set "INGRESS_HOST=^<pending^>"

echo.
echo ==============================================
echo   Deployment Complete!
echo   Application URL: http://!INGRESS_HOST!
echo   Health Check:    http://!INGRESS_HOST!/actuator/health
echo.
echo   Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
echo ==============================================

:: Cleanup temp files
rmdir /s /q "!TEMP_K8S_DIR!" >nul 2>&1

endlocal
