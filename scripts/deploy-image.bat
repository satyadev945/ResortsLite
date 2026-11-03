@echo off
setlocal enabledelayedexpansion

rem =============================================================
rem deploy-image.bat — Deploy ResortsLite to Azure AKS
rem =============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "K8S_DIR=kubernetes"
set "TMP_DIR=%TEMP%\resortslite-k8s-deploy"

echo ==============================================
echo   ResortsLite - Deploy to Azure AKS
echo ==============================================

rem ---- Azure / AKS credentials ----
set /p "RESOURCE_GROUP=Enter Azure Resource Group name: "
if "!RESOURCE_GROUP!"=="" (
    echo ERROR: Resource group cannot be empty.
    exit /b 1
)

set /p "CLUSTER_NAME=Enter AKS Cluster name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: AKS cluster name cannot be empty.
    exit /b 1
)

rem ---- Docker image URI ----
set /p "IMAGE_URI=Enter full Docker image URI (e.g. myregistry.azurecr.io/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Image URI cannot be empty.
    exit /b 1
)

echo.
echo --- Application Environment Variables ---
echo (Press Enter to keep the default value for any variable)

set /p "SPRING_DATASOURCE_URL=Enter SPRING_DATASOURCE_URL [jdbc:oracle:thin:@localhost:1521:ORCL]: "
if "!SPRING_DATASOURCE_URL!"=="" set "SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL"

set /p "SPRING_DATASOURCE_USERNAME=Enter SPRING_DATASOURCE_USERNAME [admin]: "
if "!SPRING_DATASOURCE_USERNAME!"=="" set "SPRING_DATASOURCE_USERNAME=admin"

set /p "SPRING_DATASOURCE_PASSWORD=Enter SPRING_DATASOURCE_PASSWORD: "
if "!SPRING_DATASOURCE_PASSWORD!"=="" set "SPRING_DATASOURCE_PASSWORD="

set /p "REDIS_HOST=Enter REDIS_HOST [localhost]: "
if "!REDIS_HOST!"=="" set "REDIS_HOST=localhost"

set /p "REDIS_PORT=Enter REDIS_PORT [6380]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6380"

set /p "REDIS_PASSWORD=Enter REDIS_PASSWORD: "
if "!REDIS_PASSWORD!"=="" set "REDIS_PASSWORD="

set /p "REDIS_SSL=Enter REDIS_SSL [true]: "
if "!REDIS_SSL!"=="" set "REDIS_SSL=true"

set /p "PAYMENT_API_URL=Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-service:9090/payments/charge"

set /p "REPORT_BASE_PATH=Enter REPORT_BASE_PATH [/var/reports]: "
if "!REPORT_BASE_PATH!"=="" set "REPORT_BASE_PATH=/var/reports"

set /p "BACKUP_PATH=Enter BACKUP_PATH [/var/backups/nightly]: "
if "!BACKUP_PATH!"=="" set "BACKUP_PATH=/var/backups/nightly"

set /p "BOOKING_CACHE_TTL_SECONDS=Enter BOOKING_CACHE_TTL_SECONDS [3600]: "
if "!BOOKING_CACHE_TTL_SECONDS!"=="" set "BOOKING_CACHE_TTL_SECONDS=3600"

rem ---- Configure kubectl for AKS ----
echo.
echo Configuring kubectl for AKS cluster: !CLUSTER_NAME! ...
az aks get-credentials --resource-group "!RESOURCE_GROUP!" --name "!CLUSTER_NAME!" --overwrite-existing
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to get AKS credentials.
    exit /b 1
)

echo Verifying cluster connectivity ...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to AKS cluster.
    exit /b 1
)

rem ---- Copy manifests to temp directory ----
echo.
echo Updating Kubernetes manifests with provided values ...
if exist "!TMP_DIR!" rmdir /s /q "!TMP_DIR!"
xcopy /e /i /q "!K8S_DIR!" "!TMP_DIR!" >nul

rem ---- Substitute placeholders using PowerShell ----
powershell -NoProfile -Command ^
  "$content = Get-Content '!TMP_DIR!\deployment.yaml' -Raw;" ^
  "$content = $content -replace '\{\{IMAGE_URI\}\}', '!IMAGE_URI!';" ^
  "$content = $content -replace '\{\{SPRING_DATASOURCE_URL\}\}', '!SPRING_DATASOURCE_URL!';" ^
  "$content = $content -replace '\{\{SPRING_DATASOURCE_USERNAME\}\}', '!SPRING_DATASOURCE_USERNAME!';" ^
  "$content = $content -replace '\{\{SPRING_DATASOURCE_PASSWORD\}\}', '!SPRING_DATASOURCE_PASSWORD!';" ^
  "$content = $content -replace '\{\{REDIS_HOST\}\}', '!REDIS_HOST!';" ^
  "$content = $content -replace '\{\{REDIS_PORT\}\}', '!REDIS_PORT!';" ^
  "$content = $content -replace '\{\{REDIS_PASSWORD\}\}', '!REDIS_PASSWORD!';" ^
  "$content = $content -replace '\{\{REDIS_SSL\}\}', '!REDIS_SSL!';" ^
  "$content = $content -replace '\{\{PAYMENT_API_URL\}\}', '!PAYMENT_API_URL!';" ^
  "$content = $content -replace '\{\{REPORT_BASE_PATH\}\}', '!REPORT_BASE_PATH!';" ^
  "$content = $content -replace '\{\{BACKUP_PATH\}\}', '!BACKUP_PATH!';" ^
  "$content = $content -replace '\{\{BOOKING_CACHE_TTL_SECONDS\}\}', '!BOOKING_CACHE_TTL_SECONDS!';" ^
  "Set-Content '!TMP_DIR!\deployment.yaml' -Value $content"

if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to update deployment manifest.
    exit /b 1
)

rem ---- Apply manifests ----
echo.
echo Applying Kubernetes manifests ...

echo [1/4] Applying namespace ...
kubectl apply -f "!TMP_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo [2/4] Applying deployment ...
kubectl apply -f "!TMP_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo [3/4] Applying service ...
kubectl apply -f "!TMP_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo [4/4] Applying ingress ...
kubectl apply -f "!TMP_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

rem ---- Wait for rollout ----
echo.
echo Waiting for deployment rollout ...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed. Running rollback ...
    kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

rem ---- Verify resources ----
echo.
echo Verifying deployed resources ...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ==============================================
echo   Deployment Complete!
echo   Application URL: http://resortslite.example.com
echo   Health Check:    http://resortslite.example.com/actuator/health
echo ==============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

rem Cleanup
if exist "!TMP_DIR!" rmdir /s /q "!TMP_DIR!"

endlocal
