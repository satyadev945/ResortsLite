@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: ============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "MANIFEST_DIR=kubernetes"

echo ==============================================
echo   ResortsLite — AWS EKS Deployment Script
echo ==============================================
echo.

:: ---- AWS / EKS configuration ----------------------------
set /p "AWS_REGION=Enter AWS Region [us-east-1]: "
if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

set /p "CLUSTER_NAME=Enter EKS Cluster Name: "
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
echo Press Enter to skip any variable.
echo.

set /p "SPRING_DATASOURCE_URL=Enter SPRING_DATASOURCE_URL: "
if "!SPRING_DATASOURCE_URL!"=="" set "SPRING_DATASOURCE_URL=jdbc:oracle:thin:@^<DB_HOST^>:1521:^<DB_SID^>"

set /p "SPRING_DATASOURCE_USERNAME=Enter SPRING_DATASOURCE_USERNAME [admin]: "
if "!SPRING_DATASOURCE_USERNAME!"=="" set "SPRING_DATASOURCE_USERNAME=admin"

set /p "SPRING_DATASOURCE_PASSWORD=Enter SPRING_DATASOURCE_PASSWORD: "
if "!SPRING_DATASOURCE_PASSWORD!"=="" set "SPRING_DATASOURCE_PASSWORD=changeme"

set /p "REDIS_HOST=Enter REDIS_HOST [localhost]: "
if "!REDIS_HOST!"=="" set "REDIS_HOST=localhost"

set /p "REDIS_PORT=Enter REDIS_PORT [6379]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"

set /p "BOOKING_CACHE_TTL_SECONDS=Enter BOOKING_CACHE_TTL_SECONDS [3600]: "
if "!BOOKING_CACHE_TTL_SECONDS!"=="" set "BOOKING_CACHE_TTL_SECONDS=3600"

set /p "PAYMENT_API_URL=Enter PAYMENT_API_URL: "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-service:9090/payments/charge"

set /p "APP_PAYMENT_ENDPOINT=Enter APP_PAYMENT_ENDPOINT: "
if "!APP_PAYMENT_ENDPOINT!"=="" set "APP_PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge"

set /p "APP_INVENTORY_ENDPOINT=Enter APP_INVENTORY_ENDPOINT: "
if "!APP_INVENTORY_ENDPOINT!"=="" set "APP_INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms"

set /p "APP_NOTIFICATION_ENDPOINT=Enter APP_NOTIFICATION_ENDPOINT: "
if "!APP_NOTIFICATION_ENDPOINT!"=="" set "APP_NOTIFICATION_ENDPOINT=http://notify.internal:7070/send"

set /p "REPORT_BASE_PATH=Enter REPORT_BASE_PATH [/var/legacy/reports/]: "
if "!REPORT_BASE_PATH!"=="" set "REPORT_BASE_PATH=/var/legacy/reports/"

set /p "BACKUP_PATH=Enter BACKUP_PATH [/var/legacy/backups/]: "
if "!BACKUP_PATH!"=="" set "BACKUP_PATH=/var/legacy/backups/"

:: ---- Configure kubectl ----------------------------------
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

:: ---- Copy manifests to temp dir -------------------------
echo.
echo Updating Kubernetes manifests with provided values...
set "TMP_DIR=%TEMP%\resortslite-deploy-%RANDOM%"
mkdir "!TMP_DIR!"
xcopy /E /I /Q "!MANIFEST_DIR!" "!TMP_DIR!" >nul

:: Use PowerShell to perform sed-like replacements
powershell -NoProfile -Command ^
  "(Get-Content '!TMP_DIR!\deployment.yaml') ^
   -replace '\{\{IMAGE_URI\}\}','!IMAGE_URI!' ^
   -replace '\{\{SPRING_DATASOURCE_URL\}\}','!SPRING_DATASOURCE_URL!' ^
   -replace '\{\{SPRING_DATASOURCE_USERNAME\}\}','!SPRING_DATASOURCE_USERNAME!' ^
   -replace '\{\{SPRING_DATASOURCE_PASSWORD\}\}','!SPRING_DATASOURCE_PASSWORD!' ^
   -replace '\{\{REDIS_HOST\}\}','!REDIS_HOST!' ^
   -replace '\{\{REDIS_PORT\}\}','!REDIS_PORT!' ^
   -replace '\{\{BOOKING_CACHE_TTL_SECONDS\}\}','!BOOKING_CACHE_TTL_SECONDS!' ^
   -replace '\{\{PAYMENT_API_URL\}\}','!PAYMENT_API_URL!' ^
   -replace '\{\{APP_PAYMENT_ENDPOINT\}\}','!APP_PAYMENT_ENDPOINT!' ^
   -replace '\{\{APP_INVENTORY_ENDPOINT\}\}','!APP_INVENTORY_ENDPOINT!' ^
   -replace '\{\{APP_NOTIFICATION_ENDPOINT\}\}','!APP_NOTIFICATION_ENDPOINT!' ^
   -replace '\{\{REPORT_BASE_PATH\}\}','!REPORT_BASE_PATH!' ^
   -replace '\{\{BACKUP_PATH\}\}','!BACKUP_PATH!' ^
   | Set-Content '!TMP_DIR!\deployment.yaml'"
if !ERRORLEVEL! neq 0 (
  echo ERROR: Failed to update deployment manifest.
  exit /b 1
)

:: ---- Apply manifests in order ---------------------------
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

:: ---- Wait for rollout -----------------------------------
echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
  echo ERROR: Deployment rollout failed.
  echo Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
  exit /b 1
)

:: ---- Verify resources -----------------------------------
echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ==============================================
echo   Deployment complete!
echo   Health check: http://^<INGRESS_HOST^>/actuator/health
echo ==============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

:: ---- Cleanup --------------------------------------------
rmdir /S /Q "!TMP_DIR!"

endlocal
