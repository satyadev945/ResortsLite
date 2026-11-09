@echo off
setlocal enabledelayedexpansion

set APP_NAME=resortslite
set NAMESPACE=resortslite

echo ============================================
echo   ResortsLite - Deploy to Azure AKS
echo ============================================
echo.

set /p RESOURCE_GROUP="Enter Azure Resource Group name: "
if "!RESOURCE_GROUP!"=="" (
    echo Resource group cannot be empty. Exiting.
    exit /b 1
)

set /p CLUSTER_NAME="Enter AKS Cluster name: "
if "!CLUSTER_NAME!"=="" (
    echo AKS cluster name cannot be empty. Exiting.
    exit /b 1
)

set /p IMAGE_URI="Enter full Docker image URI (e.g., myregistry.azurecr.io/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo Image URI cannot be empty. Exiting.
    exit /b 1
)

echo.
echo --- Application Environment Variables ---
echo Press Enter to skip any variable.
echo.

set /p SPRING_DATASOURCE_URL_VAL="Enter SPRING_DATASOURCE_URL: "
set /p SPRING_DATASOURCE_USERNAME_VAL="Enter SPRING_DATASOURCE_USERNAME: "
set /p SPRING_DATASOURCE_PASSWORD_VAL="Enter SPRING_DATASOURCE_PASSWORD: "
set /p REDIS_HOST_VAL="Enter REDIS_HOST (Azure Cache for Redis hostname): "
set /p REDIS_PORT_VAL="Enter REDIS_PORT (default 6380): "
set /p REDIS_PASSWORD_VAL="Enter REDIS_PASSWORD: "
set /p REDIS_SSL_VAL="Enter REDIS_SSL (true/false, default true): "
set /p APP_PAYMENT_ENDPOINT_VAL="Enter APP_PAYMENT_ENDPOINT: "
set /p APP_INVENTORY_ENDPOINT_VAL="Enter APP_INVENTORY_ENDPOINT: "
set /p APP_NOTIFICATION_ENDPOINT_VAL="Enter APP_NOTIFICATION_ENDPOINT: "
set /p REPORT_BASE_PATH_VAL="Enter REPORT_BASE_PATH (default /var/reports): "
set /p BACKUP_PATH_VAL="Enter BACKUP_PATH (default /var/backups/nightly): "

if "!REDIS_PORT_VAL!"=="" set REDIS_PORT_VAL=6380
if "!REDIS_SSL_VAL!"=="" set REDIS_SSL_VAL=true
if "!REPORT_BASE_PATH_VAL!"=="" set REPORT_BASE_PATH_VAL=/var/reports
if "!BACKUP_PATH_VAL!"=="" set BACKUP_PATH_VAL=/var/backups/nightly

echo.
echo --- Configuring kubectl for AKS ---
az aks get-credentials --resource-group !RESOURCE_GROUP! --name !CLUSTER_NAME! --overwrite-existing
if !ERRORLEVEL! neq 0 (
    echo Failed to configure kubectl. Exiting.
    exit /b 1
)
echo kubectl configured successfully.

echo.
echo --- Verifying cluster connectivity ---
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo Cannot connect to AKS cluster. Exiting.
    exit /b 1
)

echo.
echo --- Updating Kubernetes manifests ---

copy kubernetes\deployment.yaml kubernetes\deployment.yaml.bak >nul

powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content kubernetes\deployment.yaml"

if not "!SPRING_DATASOURCE_URL_VAL!"=="" (
    powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{SPRING_DATASOURCE_URL}}', '!SPRING_DATASOURCE_URL_VAL!' | Set-Content kubernetes\deployment.yaml"
)
if not "!SPRING_DATASOURCE_USERNAME_VAL!"=="" (
    powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{SPRING_DATASOURCE_USERNAME}}', '!SPRING_DATASOURCE_USERNAME_VAL!' | Set-Content kubernetes\deployment.yaml"
)
if not "!SPRING_DATASOURCE_PASSWORD_VAL!"=="" (
    powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{SPRING_DATASOURCE_PASSWORD}}', '!SPRING_DATASOURCE_PASSWORD_VAL!' | Set-Content kubernetes\deployment.yaml"
)
if not "!REDIS_HOST_VAL!"=="" (
    powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{REDIS_HOST}}', '!REDIS_HOST_VAL!' | Set-Content kubernetes\deployment.yaml"
)
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{REDIS_PORT}}', '!REDIS_PORT_VAL!' | Set-Content kubernetes\deployment.yaml"
if not "!REDIS_PASSWORD_VAL!"=="" (
    powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{REDIS_PASSWORD}}', '!REDIS_PASSWORD_VAL!' | Set-Content kubernetes\deployment.yaml"
)
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{REDIS_SSL}}', '!REDIS_SSL_VAL!' | Set-Content kubernetes\deployment.yaml"
if not "!APP_PAYMENT_ENDPOINT_VAL!"=="" (
    powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{APP_PAYMENT_ENDPOINT}}', '!APP_PAYMENT_ENDPOINT_VAL!' | Set-Content kubernetes\deployment.yaml"
)
if not "!APP_INVENTORY_ENDPOINT_VAL!"=="" (
    powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{APP_INVENTORY_ENDPOINT}}', '!APP_INVENTORY_ENDPOINT_VAL!' | Set-Content kubernetes\deployment.yaml"
)
if not "!APP_NOTIFICATION_ENDPOINT_VAL!"=="" (
    powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{APP_NOTIFICATION_ENDPOINT}}', '!APP_NOTIFICATION_ENDPOINT_VAL!' | Set-Content kubernetes\deployment.yaml"
)
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{REPORT_BASE_PATH}}', '!REPORT_BASE_PATH_VAL!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{BACKUP_PATH}}', '!BACKUP_PATH_VAL!' | Set-Content kubernetes\deployment.yaml"

echo Manifests updated.

echo.
echo --- Applying Kubernetes manifests ---
echo Applying namespace...
kubectl apply -f kubernetes\namespace.yaml
if !ERRORLEVEL! neq 0 ( echo Failed to apply namespace. & exit /b 1 )

echo Applying deployment...
kubectl apply -f kubernetes\deployment.yaml
if !ERRORLEVEL! neq 0 ( echo Failed to apply deployment. & exit /b 1 )

echo Applying service...
kubectl apply -f kubernetes\service.yaml
if !ERRORLEVEL! neq 0 ( echo Failed to apply service. & exit /b 1 )

echo Applying ingress...
kubectl apply -f kubernetes\ingress.yaml
if !ERRORLEVEL! neq 0 ( echo Failed to apply ingress. & exit /b 1 )

echo.
echo --- Waiting for deployment rollout ---
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo Deployment rollout failed.
    echo To rollback run: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

echo.
echo --- Verifying deployed resources ---
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo --- Application Access ---
echo Application URL: http://resortslite.example.com
echo Update your DNS to point resortslite.example.com to the ingress IP.

echo.
echo --- Rollback Instructions ---
echo If deployment fails, run:
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

copy kubernetes\deployment.yaml.bak kubernetes\deployment.yaml >nul
del kubernetes\deployment.yaml.bak >nul

echo.
echo ============================================
echo   Deployment Complete!
echo ============================================

endlocal
