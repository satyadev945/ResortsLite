@echo off
setlocal enabledelayedexpansion

:: =============================================================
:: deploy-image.bat — Deploy ResortsLite to Azure AKS (Windows)
:: =============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "MANIFESTS_DIR=kubernetes"
set "TEMP_DIR=%TEMP%\resortslite-k8s-deploy"

echo ==============================================
echo   ResortsLite -- AKS Deployment Script
echo ==============================================
echo.

:: ---- Azure / AKS credentials ----
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

set /p "IMAGE_URI=Enter full Docker image URI (e.g. myregistry.azurecr.io/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Image URI cannot be empty.
    exit /b 1
)

echo.
echo --- Application Environment Variables ---
echo Press Enter to skip any variable.
echo.

set /p "SPRING_DATASOURCE_URL=Enter SPRING_DATASOURCE_URL (Oracle JDBC URL): "
set /p "SPRING_DATASOURCE_USERNAME=Enter SPRING_DATASOURCE_USERNAME: "
set /p "SPRING_DATASOURCE_PASSWORD=Enter SPRING_DATASOURCE_PASSWORD: "
set /p "REDIS_HOST=Enter REDIS_HOST (Azure Cache for Redis hostname): "
set /p "REDIS_PORT=Enter REDIS_PORT [6380]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6380"
set /p "REDIS_PASSWORD=Enter REDIS_PASSWORD (Azure Cache for Redis access key): "
set /p "PAYMENT_API_URL=Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-service:9090/payments/charge"
set /p "APP_PAYMENT_ENDPOINT=Enter APP_PAYMENT_ENDPOINT [http://payment-svc:9090/charge]: "
if "!APP_PAYMENT_ENDPOINT!"=="" set "APP_PAYMENT_ENDPOINT=http://payment-svc:9090/charge"
set /p "APP_INVENTORY_ENDPOINT=Enter APP_INVENTORY_ENDPOINT [http://inventory-svc:8081/rooms]: "
if "!APP_INVENTORY_ENDPOINT!"=="" set "APP_INVENTORY_ENDPOINT=http://inventory-svc:8081/rooms"
set /p "APP_NOTIFICATION_ENDPOINT=Enter APP_NOTIFICATION_ENDPOINT [http://notify-svc:7070/send]: "
if "!APP_NOTIFICATION_ENDPOINT!"=="" set "APP_NOTIFICATION_ENDPOINT=http://notify-svc:7070/send"
set /p "REPORT_BASE_PATH=Enter REPORT_BASE_PATH [/tmp/reports]: "
if "!REPORT_BASE_PATH!"=="" set "REPORT_BASE_PATH=/tmp/reports"
set /p "BACKUP_PATH=Enter BACKUP_PATH [/tmp/backups]: "
if "!BACKUP_PATH!"=="" set "BACKUP_PATH=/tmp/backups"

echo.
echo --- Configuring kubectl for AKS cluster: !CLUSTER_NAME! ---
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

echo.
echo --- Preparing Kubernetes manifests ---
if exist "!TEMP_DIR!" rmdir /s /q "!TEMP_DIR!"
xcopy /e /i /q "!MANIFESTS_DIR!" "!TEMP_DIR!" >nul
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to copy manifests.
    exit /b 1
)

set "DEPLOY_FILE=!TEMP_DIR!\deployment.yaml"

:: Substitute placeholders using PowerShell
powershell -NoProfile -Command ^
  "(Get-Content '!DEPLOY_FILE!') ^
   -replace '\{\{IMAGE_URI\}\}','!IMAGE_URI!' ^
   -replace '\{\{SPRING_DATASOURCE_URL\}\}','!SPRING_DATASOURCE_URL!' ^
   -replace '\{\{SPRING_DATASOURCE_USERNAME\}\}','!SPRING_DATASOURCE_USERNAME!' ^
   -replace '\{\{SPRING_DATASOURCE_PASSWORD\}\}','!SPRING_DATASOURCE_PASSWORD!' ^
   -replace '\{\{REDIS_HOST\}\}','!REDIS_HOST!' ^
   -replace '\{\{REDIS_PORT\}\}','!REDIS_PORT!' ^
   -replace '\{\{REDIS_PASSWORD\}\}','!REDIS_PASSWORD!' ^
   -replace '\{\{PAYMENT_API_URL\}\}','!PAYMENT_API_URL!' ^
   -replace '\{\{APP_PAYMENT_ENDPOINT\}\}','!APP_PAYMENT_ENDPOINT!' ^
   -replace '\{\{APP_INVENTORY_ENDPOINT\}\}','!APP_INVENTORY_ENDPOINT!' ^
   -replace '\{\{APP_NOTIFICATION_ENDPOINT\}\}','!APP_NOTIFICATION_ENDPOINT!' ^
   -replace '\{\{REPORT_BASE_PATH\}\}','!REPORT_BASE_PATH!' ^
   -replace '\{\{BACKUP_PATH\}\}','!BACKUP_PATH!' ^
   | Set-Content '!DEPLOY_FILE!'"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to substitute placeholders.
    exit /b 1
)

echo.
echo --- Applying Kubernetes manifests ---
echo Applying namespace ...
kubectl apply -f "!TEMP_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo Applying deployment ...
kubectl apply -f "!TEMP_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo Applying service ...
kubectl apply -f "!TEMP_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo Applying ingress ...
kubectl apply -f "!TEMP_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

echo.
echo --- Waiting for deployment rollout ---
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed. Initiating rollback ...
    kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    echo Rollback initiated. Check pod status with: kubectl get pods -n !NAMESPACE!
    exit /b 1
)

echo.
echo --- Verifying deployed resources ---
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ==============================================
echo   Deployment completed successfully!
echo   Namespace: !NAMESPACE!
echo   Image:     !IMAGE_URI!
echo ==============================================
echo.
echo Useful commands:
echo   kubectl get pods -n !NAMESPACE!
echo   kubectl logs -f deployment/!APP_NAME! -n !NAMESPACE!
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

:: Cleanup temp files
rmdir /s /q "!TEMP_DIR!" >nul 2>&1

endlocal
exit /b 0
