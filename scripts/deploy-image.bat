@echo off
setlocal enabledelayedexpansion

:: =============================================================
:: deploy-image.bat — Deploy orcappdbmmono to Azure AKS
:: =============================================================

set "APP_NAME=orcappdbmmono"
set "NAMESPACE=orcappdbmmono"
set "K8S_DIR=%~dp0..\kubernetes"

echo ==============================================
echo   Deploy to Azure AKS: %APP_NAME%
echo ==============================================
echo.

:: --- Azure / AKS credentials ---
set /p RESOURCE_GROUP="Enter Azure Resource Group name: "
if "!RESOURCE_GROUP!"=="" (
    echo ERROR: Resource group cannot be empty.
    exit /b 1
)

set /p CLUSTER_NAME="Enter AKS Cluster name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: Cluster name cannot be empty.
    exit /b 1
)

:: --- Docker image URI ---
set /p IMAGE_URI="Enter full Docker image URI (e.g. myregistry.azurecr.io/orcappdbmmono:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Image URI cannot be empty.
    exit /b 1
)

echo.
echo --- Application Environment Variables ---
echo Press Enter to skip any variable.
echo.

set /p SPRING_DATASOURCE_URL="Enter SPRING_DATASOURCE_URL (Oracle JDBC URL): "
set /p SPRING_DATASOURCE_USERNAME="Enter SPRING_DATASOURCE_USERNAME: "
set /p SPRING_DATASOURCE_PASSWORD="Enter SPRING_DATASOURCE_PASSWORD: "
set /p REDIS_HOST="Enter REDIS_HOST (Azure Cache for Redis hostname): "
set /p REDIS_PORT="Enter REDIS_PORT [default: 6380]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6380"
set /p REDIS_PASSWORD="Enter REDIS_PASSWORD: "
set /p REDIS_SSL="Enter REDIS_SSL [default: true]: "
if "!REDIS_SSL!"=="" set "REDIS_SSL=true"
set /p PAYMENT_API_URL="Enter PAYMENT_API_URL: "
set /p APP_PAYMENT_ENDPOINT="Enter APP_PAYMENT_ENDPOINT: "
set /p APP_INVENTORY_ENDPOINT="Enter APP_INVENTORY_ENDPOINT: "
set /p APP_NOTIFICATION_ENDPOINT="Enter APP_NOTIFICATION_ENDPOINT: "
set /p REPORTS_BASE_PATH="Enter REPORTS_BASE_PATH [default: /var/reports]: "
if "!REPORTS_BASE_PATH!"=="" set "REPORTS_BASE_PATH=/var/reports"
set /p REPORTS_BACKUP_PATH="Enter REPORTS_BACKUP_PATH [default: /var/backups/nightly]: "
if "!REPORTS_BACKUP_PATH!"=="" set "REPORTS_BACKUP_PATH=/var/backups/nightly"

echo.
echo --- Configuring kubectl for AKS ---
az aks get-credentials --resource-group "!RESOURCE_GROUP!" --name "!CLUSTER_NAME!" --overwrite-existing
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to get AKS credentials.
    exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to AKS cluster.
    exit /b 1
)

echo.
echo --- Updating Kubernetes manifests with PowerShell ---

:: Use PowerShell to perform sed-like replacements on deployment.yaml
set "DEPLOY_SRC=%K8S_DIR%\deployment.yaml"
set "DEPLOY_TMP=%TEMP%\deployment_deploy.yaml"

powershell -NoProfile -Command ^
    "(Get-Content '%DEPLOY_SRC%') ^
     -replace '\{\{IMAGE_URI\}\}', '!IMAGE_URI!' ^
     -replace '\{\{SPRING_DATASOURCE_URL\}\}', '!SPRING_DATASOURCE_URL!' ^
     -replace '\{\{SPRING_DATASOURCE_USERNAME\}\}', '!SPRING_DATASOURCE_USERNAME!' ^
     -replace '\{\{SPRING_DATASOURCE_PASSWORD\}\}', '!SPRING_DATASOURCE_PASSWORD!' ^
     -replace '\{\{REDIS_HOST\}\}', '!REDIS_HOST!' ^
     -replace '\{\{REDIS_PORT\}\}', '!REDIS_PORT!' ^
     -replace '\{\{REDIS_PASSWORD\}\}', '!REDIS_PASSWORD!' ^
     -replace '\{\{REDIS_SSL\}\}', '!REDIS_SSL!' ^
     -replace '\{\{PAYMENT_API_URL\}\}', '!PAYMENT_API_URL!' ^
     -replace '\{\{APP_PAYMENT_ENDPOINT\}\}', '!APP_PAYMENT_ENDPOINT!' ^
     -replace '\{\{APP_INVENTORY_ENDPOINT\}\}', '!APP_INVENTORY_ENDPOINT!' ^
     -replace '\{\{APP_NOTIFICATION_ENDPOINT\}\}', '!APP_NOTIFICATION_ENDPOINT!' ^
     -replace '\{\{REPORTS_BASE_PATH\}\}', '!REPORTS_BASE_PATH!' ^
     -replace '\{\{REPORTS_BACKUP_PATH\}\}', '!REPORTS_BACKUP_PATH!' ^
     | Set-Content '%DEPLOY_TMP%'"

if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to update deployment manifest.
    exit /b 1
)

echo.
echo --- Applying Kubernetes manifests ---
kubectl apply -f "%K8S_DIR%\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

kubectl apply -f "%DEPLOY_TMP%"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

kubectl apply -f "%K8S_DIR%\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

kubectl apply -f "%K8S_DIR%\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

echo.
echo --- Waiting for rollout ---
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed.
    echo Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

echo.
echo --- Verifying resources ---
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ==============================================
echo   DEPLOYMENT COMPLETE
echo   Application URL: http://orcappdbmmono.example.com
echo   Health check:    http://orcappdbmmono.example.com/actuator/health
echo ==============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

:: Cleanup temp file
del /f /q "%DEPLOY_TMP%" 2>nul

endlocal
