@echo off
setlocal enabledelayedexpansion

set "NAMESPACE=resortslite"
set "APP_NAME=resortslite"

set /p RESOURCE_GROUP="Enter Azure resource group: "
set /p CLUSTER_NAME="Enter AKS cluster name: "
set /p IMAGE_URI="Enter Docker image URI (including tag): "

if "%RESOURCE_GROUP%"=="" (
  echo Resource group is required
  exit /b 1
)
if "%CLUSTER_NAME%"=="" (
  echo Cluster name is required
  exit /b 1
)
if "%IMAGE_URI%"=="" (
  echo Image URI is required
  exit /b 1
)

set /p SPRING_DATASOURCE_URL="Enter value for SPRING_DATASOURCE_URL (or press Enter to skip): "
set /p SPRING_DATASOURCE_USERNAME="Enter value for SPRING_DATASOURCE_USERNAME (or press Enter to skip): "
set /p SPRING_DATASOURCE_PASSWORD="Enter value for SPRING_DATASOURCE_PASSWORD (or press Enter to skip): "
set /p APP_PAYMENT_ENDPOINT="Enter value for APP_PAYMENT_ENDPOINT (or press Enter to skip): "
set /p APP_INVENTORY_ENDPOINT="Enter value for APP_INVENTORY_ENDPOINT (or press Enter to skip): "
set /p APP_NOTIFICATION_ENDPOINT="Enter value for APP_NOTIFICATION_ENDPOINT (or press Enter to skip): "
set /p CACHE_PROVIDER="Enter value for CACHE_PROVIDER (or press Enter to skip) [redis]: "
if "!CACHE_PROVIDER!"=="" set "CACHE_PROVIDER=redis"

set "TMP_DIR=%TEMP%\resortslite-k8s-%RANDOM%"
mkdir "!TMP_DIR!"
copy /Y kubernetes\*.yaml "!TMP_DIR!\" >nul

powershell -NoProfile -Command "(Get-Content '!TMP_DIR!\deployment.yaml') -replace '\{\{IMAGE_URI\}\}','!IMAGE_URI!' -replace '\{\{SPRING_DATASOURCE_URL\}\}','!SPRING_DATASOURCE_URL!' -replace '\{\{SPRING_DATASOURCE_USERNAME\}\}','!SPRING_DATASOURCE_USERNAME!' -replace '\{\{SPRING_DATASOURCE_PASSWORD\}\}','!SPRING_DATASOURCE_PASSWORD!' -replace '\{\{APP_PAYMENT_ENDPOINT\}\}','!APP_PAYMENT_ENDPOINT!' -replace '\{\{APP_INVENTORY_ENDPOINT\}\}','!APP_INVENTORY_ENDPOINT!' -replace '\{\{APP_NOTIFICATION_ENDPOINT\}\}','!APP_NOTIFICATION_ENDPOINT!' -replace '\{\{CACHE_PROVIDER\}\}','!CACHE_PROVIDER!' | Set-Content '!TMP_DIR!\deployment.yaml'"
if %ERRORLEVEL% neq 0 (
  echo Failed to update deployment manifest
  exit /b 1
)

az aks get-credentials --resource-group "%RESOURCE_GROUP%" --name "%CLUSTER_NAME%" --overwrite-existing
if %ERRORLEVEL% neq 0 (
  echo Failed to configure kubectl
  exit /b 1
)

kubectl cluster-info
if %ERRORLEVEL% neq 0 (
  echo Unable to connect to cluster
  exit /b 1
)

kubectl apply -f "!TMP_DIR!\namespace.yaml"
if %ERRORLEVEL% neq 0 exit /b 1
kubectl apply -f "!TMP_DIR!\deployment.yaml"
if %ERRORLEVEL% neq 0 exit /b 1
kubectl apply -f "!TMP_DIR!\service.yaml"
if %ERRORLEVEL% neq 0 exit /b 1
kubectl apply -f "!TMP_DIR!\ingress.yaml"
if %ERRORLEVEL% neq 0 exit /b 1

kubectl rollout status deployment/%APP_NAME% -n %NAMESPACE%
if %ERRORLEVEL% neq 0 (
  echo Deployment rollout failed
  echo Rollback command: kubectl rollout undo deployment/%APP_NAME% -n %NAMESPACE%
  exit /b 1
)

kubectl get pods,svc,ingress -n %NAMESPACE%
echo Application URL: http://resortslite.example.com/
echo Rollback command: kubectl rollout undo deployment/%APP_NAME% -n %NAMESPACE%
rmdir /S /Q "!TMP_DIR!"
endlocal
