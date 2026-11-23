@echo off
setlocal enabledelayedexpansion

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "SCRIPT_DIR=%~dp0"
set "K8S_SRC_DIR=%SCRIPT_DIR%..\kubernetes"
set "WORK_DIR=%TEMP%\resortslite-k8s-%RANDOM%"

echo === Deploy !APP_NAME! to Azure AKS ===

where az >nul 2>&1
if !ERRORLEVEL! neq 0 (
  echo ERROR: Azure CLI - az - is not installed or not in PATH
  exit /b 1
)
where kubectl >nul 2>&1
if !ERRORLEVEL! neq 0 (
  echo ERROR: kubectl is not installed or not in PATH
  exit /b 1
)

set /p RESOURCE_GROUP="Enter Azure resource group: "
if "!RESOURCE_GROUP!"=="" (
  echo ERROR: resource group is required
  exit /b 1
)
set /p CLUSTER_NAME="Enter AKS cluster name: "
if "!CLUSTER_NAME!"=="" (
  echo ERROR: cluster name is required
  exit /b 1
)
set /p IMAGE_URI="Enter Docker image URI, for example myacr.azurecr.io/resortslite:latest: "
if "!IMAGE_URI!"=="" (
  echo ERROR: image URI is required
  exit /b 1
)

echo.
echo --- Application configuration ---
echo Note: values typed here are visible on screen.
set /p DEPLOY_DB_URL="Enter SPRING_DATASOURCE_URL: "
if "!DEPLOY_DB_URL!"=="" (
  echo ERROR: database URL is required
  exit /b 1
)
set /p DEPLOY_DB_USERNAME="Enter database username: "
if "!DEPLOY_DB_USERNAME!"=="" (
  echo ERROR: database username is required
  exit /b 1
)
set /p DEPLOY_DB_PASSWORD="Enter database password: "
if "!DEPLOY_DB_PASSWORD!"=="" (
  echo ERROR: database password is required
  exit /b 1
)
set "DEPLOY_REDIS_HOST="
set /p DEPLOY_REDIS_HOST="Enter REDIS_HOST [localhost]: "
if "!DEPLOY_REDIS_HOST!"=="" set "DEPLOY_REDIS_HOST=localhost"
set "DEPLOY_REDIS_PORT="
set /p DEPLOY_REDIS_PORT="Enter REDIS_PORT [6380]: "
if "!DEPLOY_REDIS_PORT!"=="" set "DEPLOY_REDIS_PORT=6380"
set "DEPLOY_REDIS_SSL="
set /p DEPLOY_REDIS_SSL="Enter REDIS_SSL [true]: "
if "!DEPLOY_REDIS_SSL!"=="" set "DEPLOY_REDIS_SSL=true"
set "DEPLOY_REDIS_PASSWORD="
set /p DEPLOY_REDIS_PASSWORD="Enter REDIS_PASSWORD or press Enter to skip: "
set "DEPLOY_PAYMENT_ENDPOINT="
set /p DEPLOY_PAYMENT_ENDPOINT="Enter payment endpoint [http://payment-svc.internal:9090/charge]: "
if "!DEPLOY_PAYMENT_ENDPOINT!"=="" set "DEPLOY_PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge"
set "DEPLOY_INVENTORY_ENDPOINT="
set /p DEPLOY_INVENTORY_ENDPOINT="Enter inventory endpoint [http://inventory-svc.internal:8081/rooms]: "
if "!DEPLOY_INVENTORY_ENDPOINT!"=="" set "DEPLOY_INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms"
set "DEPLOY_NOTIFICATION_ENDPOINT="
set /p DEPLOY_NOTIFICATION_ENDPOINT="Enter notification endpoint [http://notify.internal:7070/send]: "
if "!DEPLOY_NOTIFICATION_ENDPOINT!"=="" set "DEPLOY_NOTIFICATION_ENDPOINT=http://notify.internal:7070/send"
set "DEPLOY_INGRESS_HOST="
set /p DEPLOY_INGRESS_HOST="Enter ingress host name [resortslite.example.com]: "
if "!DEPLOY_INGRESS_HOST!"=="" set "DEPLOY_INGRESS_HOST=resortslite.example.com"
set "DEPLOY_IMAGE_URI=!IMAGE_URI!"
set "DEPLOY_NAMESPACE=!NAMESPACE!"

REM Render manifests into a temp copy so templates keep their placeholders
mkdir "!WORK_DIR!"
copy /Y "!K8S_SRC_DIR!\*.yaml" "!WORK_DIR!\" >nul
if !ERRORLEVEL! neq 0 (
  echo ERROR: could not copy manifests
  exit /b 1
)

for %%F in ("!WORK_DIR!\*.yaml") do (
  powershell -NoProfile -ExecutionPolicy Bypass -File "!SCRIPT_DIR!render-manifests.ps1" -Path "%%~fF"
  if !ERRORLEVEL! neq 0 (
    echo ERROR: failed to render %%~nxF
    exit /b 1
  )
)

echo Configuring kubectl for !CLUSTER_NAME! ...
az aks get-credentials --resource-group "!RESOURCE_GROUP!" --name "!CLUSTER_NAME!" --overwrite-existing
if !ERRORLEVEL! neq 0 (
  echo ERROR: az aks get-credentials failed
  exit /b 1
)

echo Verifying cluster connectivity ...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
  echo ERROR: cannot reach cluster
  exit /b 1
)

echo Applying manifests ...
kubectl apply -f "!WORK_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 goto :apply_failed
kubectl apply -f "!WORK_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 goto :apply_failed
kubectl apply -f "!WORK_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 goto :apply_failed
kubectl apply -f "!WORK_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 goto :apply_failed

echo Waiting for rollout ...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
  echo ERROR: rollout failed. Inspect with:
  echo   kubectl describe pods -n !NAMESPACE!
  echo   kubectl logs -l app=!APP_NAME! -n !NAMESPACE! --tail=100
  echo Roll back with:
  echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
  rmdir /s /q "!WORK_DIR!" >nul 2>&1
  exit /b 1
)

echo.
kubectl get pods,svc,ingress -n !NAMESPACE!
echo.
echo Expected application URL: http://!DEPLOY_INGRESS_HOST!
echo Ingress IP: kubectl get ingress resortslite-ingress -n !NAMESPACE!
echo Rollback if needed: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
rmdir /s /q "!WORK_DIR!" >nul 2>&1
endlocal
exit /b 0

:apply_failed
echo ERROR: kubectl apply failed
rmdir /s /q "!WORK_DIR!" >nul 2>&1
exit /b 1
