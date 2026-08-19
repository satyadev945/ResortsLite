@echo off
setlocal enabledelayedexpansion

:: =============================================================
:: deploy-image.bat — Deploy ResortsLite to Azure AKS (Windows)
:: =============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"

echo ==============================================
echo   ResortsLite - Deploy to Azure AKS
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

:: ---- Docker image URI ----
set /p "IMAGE_URI=Enter full Docker image URI (e.g. myregistry.azurecr.io/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Image URI cannot be empty.
    exit /b 1
)

echo.
echo --- Application Environment Variables ---
echo Press Enter to keep the default value shown in brackets.
echo.

:: Redis
set /p "REDIS_HOST_VAL=Enter REDIS_HOST [redis-master.resortslite.svc.cluster.local]: "
if "!REDIS_HOST_VAL!"=="" set "REDIS_HOST_VAL=redis-master.resortslite.svc.cluster.local"

set /p "REDIS_PORT_VAL=Enter REDIS_PORT [6379]: "
if "!REDIS_PORT_VAL!"=="" set "REDIS_PORT_VAL=6379"

set /p "BOOKING_CACHE_TTL_VAL=Enter BOOKING_CACHE_TTL_SECONDS [3600]: "
if "!BOOKING_CACHE_TTL_VAL!"=="" set "BOOKING_CACHE_TTL_VAL=3600"

:: Azure Service Bus
set /p "SB_CONN_STR=Enter AZURE_SERVICE_BUS_CONNECTION_STRING (or press Enter to skip): "

set /p "SB_BOOKING_QUEUE=Enter AZURE_SERVICE_BUS_BOOKING_QUEUE [booking-events-queue]: "
if "!SB_BOOKING_QUEUE!"=="" set "SB_BOOKING_QUEUE=booking-events-queue"

set /p "SB_REPORT_QUEUE=Enter AZURE_SERVICE_BUS_REPORT_QUEUE [report-events-queue]: "
if "!SB_REPORT_QUEUE!"=="" set "SB_REPORT_QUEUE=report-events-queue"

:: JWT
set /p "JWT_KEY=Enter JWT_SECRET_KEY (or press Enter to use default): "
if "!JWT_KEY!"=="" set "JWT_KEY=default-dev-secret-key-replace-in-production"

:: DB Host
set /p "DB_HOST_VAL=Enter DB_HOST [localhost]: "
if "!DB_HOST_VAL!"=="" set "DB_HOST_VAL=localhost"

echo.
echo ==============================================
echo   Configuring kubectl for AKS cluster ...
echo ==============================================
az aks get-credentials --resource-group "!RESOURCE_GROUP!" --name "!CLUSTER_NAME!" --overwrite-existing
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to get AKS credentials.
    exit /b 1
)

echo.
echo Verifying cluster connectivity ...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to AKS cluster.
    exit /b 1
)

echo.
echo ==============================================
echo   Updating Kubernetes manifests ...
echo ==============================================

:: Create temp directory for modified manifests
set "DEPLOY_TMP=%TEMP%\resortslite-deploy-%RANDOM%"
mkdir "!DEPLOY_TMP!"

copy "kubernetes\namespace.yaml"  "!DEPLOY_TMP!\namespace.yaml"  >nul
copy "kubernetes\deployment.yaml" "!DEPLOY_TMP!\deployment.yaml" >nul
copy "kubernetes\service.yaml"    "!DEPLOY_TMP!\service.yaml"    >nul
copy "kubernetes\ingress.yaml"    "!DEPLOY_TMP!\ingress.yaml"    >nul

:: Replace placeholders using PowerShell
powershell -NoProfile -Command ^
  "(Get-Content '!DEPLOY_TMP!\deployment.yaml') ^
   -replace '{{IMAGE_URI}}','!IMAGE_URI!' ^
   -replace '{{REDIS_HOST}}','!REDIS_HOST_VAL!' ^
   -replace '{{REDIS_PORT}}','!REDIS_PORT_VAL!' ^
   -replace '{{BOOKING_CACHE_TTL_SECONDS}}','!BOOKING_CACHE_TTL_VAL!' ^
   -replace '{{AZURE_SERVICE_BUS_CONNECTION_STRING}}','!SB_CONN_STR!' ^
   -replace '{{AZURE_SERVICE_BUS_BOOKING_QUEUE}}','!SB_BOOKING_QUEUE!' ^
   -replace '{{AZURE_SERVICE_BUS_REPORT_QUEUE}}','!SB_REPORT_QUEUE!' ^
   -replace '{{JWT_SECRET_KEY}}','!JWT_KEY!' ^
   -replace '{{DB_HOST}}','!DB_HOST_VAL!' ^
   | Set-Content '!DEPLOY_TMP!\deployment.yaml'"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to update deployment manifest.
    exit /b 1
)

echo.
echo ==============================================
echo   Applying Kubernetes manifests ...
echo ==============================================

echo [1/4] Applying namespace ...
kubectl apply -f "!DEPLOY_TMP!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo [2/4] Applying deployment ...
kubectl apply -f "!DEPLOY_TMP!\deployment.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo [3/4] Applying service ...
kubectl apply -f "!DEPLOY_TMP!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo [4/4] Applying ingress ...
kubectl apply -f "!DEPLOY_TMP!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

echo.
echo Waiting for deployment rollout ...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed. Rolling back ...
    kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    echo Rollback initiated. Check pod logs:
    echo   kubectl logs -l app=!APP_NAME! -n !NAMESPACE! --tail=50
    exit /b 1
)

echo.
echo ==============================================
echo   Verifying deployed resources ...
echo ==============================================
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ==============================================
echo   DEPLOYMENT SUCCESSFUL
echo   Namespace : !NAMESPACE!
echo   Image     : !IMAGE_URI!
echo   App URL   : http://resortslite.example.com
echo   Health    : http://resortslite.example.com/actuator/health
echo ==============================================

:: Clean up temp directory
rmdir /s /q "!DEPLOY_TMP!"

endlocal
