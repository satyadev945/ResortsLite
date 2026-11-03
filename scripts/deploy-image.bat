@echo off
setlocal enabledelayedexpansion

set "NAMESPACE=test-comp1"
set "APP_NAME=test-comp1"

set /p RESOURCE_GROUP="Enter Azure resource group: "
set /p CLUSTER_NAME="Enter AKS cluster name: "
set /p IMAGE_URI="Enter Docker image URI (registry/repository:tag): "

if "%RESOURCE_GROUP%"=="" (
  echo Resource group is required.
  exit /b 1
)
if "%CLUSTER_NAME%"=="" (
  echo Cluster name is required.
  exit /b 1
)
if "%IMAGE_URI%"=="" (
  echo Image URI is required.
  exit /b 1
)

set /p SPRING_DATASOURCE_URL="Enter value for SPRING_DATASOURCE_URL (or press Enter to skip): "
set /p SPRING_DATASOURCE_USERNAME="Enter value for SPRING_DATASOURCE_USERNAME (or press Enter to skip): "
set /p SPRING_DATASOURCE_PASSWORD="Enter value for SPRING_DATASOURCE_PASSWORD (or press Enter to skip): "
set /p PAYMENT_ENDPOINT="Enter value for PAYMENT_ENDPOINT (or press Enter to skip): "
set /p INVENTORY_ENDPOINT="Enter value for INVENTORY_ENDPOINT (or press Enter to skip): "
set /p NOTIFICATION_ENDPOINT="Enter value for NOTIFICATION_ENDPOINT (or press Enter to skip): "
set /p REDIS_HOST="Enter value for REDIS_HOST (or press Enter to skip): "
set /p REDIS_PORT="Enter value for REDIS_PORT (or press Enter to skip): "
set /p REDIS_PASSWORD="Enter value for REDIS_PASSWORD (or press Enter to skip): "
set /p REDIS_SSL="Enter value for REDIS_SSL (or press Enter to skip): "

if "!SPRING_DATASOURCE_URL!"=="" set "SPRING_DATASOURCE_URL=jdbc:h2:mem:resortdb;DB_CLOSE_DELAY=-1"
if "!SPRING_DATASOURCE_USERNAME!"=="" set "SPRING_DATASOURCE_USERNAME=sa"
if "!PAYMENT_ENDPOINT!"=="" set "PAYMENT_ENDPOINT=https://payment.example.internal/charge"
if "!INVENTORY_ENDPOINT!"=="" set "INVENTORY_ENDPOINT=https://inventory.example.internal/rooms"
if "!NOTIFICATION_ENDPOINT!"=="" set "NOTIFICATION_ENDPOINT=https://notify.example.internal/send"
if "!REDIS_HOST!"=="" set "REDIS_HOST=redis.example.internal"
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"
if "!REDIS_SSL!"=="" set "REDIS_SSL=true"

set "TMP_DEPLOYMENT=%TEMP%\test-comp1-deployment.yaml"
copy /Y kubernetes\deployment.yaml "!TMP_DEPLOYMENT!" >nul
powershell -NoProfile -Command "(Get-Content '!TMP_DEPLOYMENT!') -replace '\{\{IMAGE_URI\}\}','!IMAGE_URI!' -replace '\{\{SPRING_DATASOURCE_URL\}\}','!SPRING_DATASOURCE_URL!' -replace '\{\{SPRING_DATASOURCE_USERNAME\}\}','!SPRING_DATASOURCE_USERNAME!' -replace '\{\{SPRING_DATASOURCE_PASSWORD\}\}','!SPRING_DATASOURCE_PASSWORD!' -replace '\{\{PAYMENT_ENDPOINT\}\}','!PAYMENT_ENDPOINT!' -replace '\{\{INVENTORY_ENDPOINT\}\}','!INVENTORY_ENDPOINT!' -replace '\{\{NOTIFICATION_ENDPOINT\}\}','!NOTIFICATION_ENDPOINT!' -replace '\{\{REDIS_HOST\}\}','!REDIS_HOST!' -replace '\{\{REDIS_PORT\}\}','!REDIS_PORT!' -replace '\{\{REDIS_PASSWORD\}\}','!REDIS_PASSWORD!' -replace '\{\{REDIS_SSL\}\}','!REDIS_SSL!' | Set-Content '!TMP_DEPLOYMENT!'"

az aks get-credentials --resource-group "%RESOURCE_GROUP%" --name "%CLUSTER_NAME%" --overwrite-existing
if %ERRORLEVEL% neq 0 (
  echo Failed to configure kubectl credentials.
  exit /b 1
)

kubectl cluster-info
if %ERRORLEVEL% neq 0 (
  echo Unable to connect to AKS cluster.
  exit /b 1
)

kubectl apply -f kubernetes\namespace.yaml
kubectl apply -f "!TMP_DEPLOYMENT!"
kubectl apply -f kubernetes\service.yaml
kubectl apply -f kubernetes\ingress.yaml
kubectl rollout status deployment/%APP_NAME% -n %NAMESPACE%
kubectl get pods,svc,ingress -n %NAMESPACE%

echo Application should be available via the ingress host configured in kubernetes\ingress.yaml
echo Rollback command: kubectl rollout undo deployment/%APP_NAME% -n %NAMESPACE%
del /Q "!TMP_DEPLOYMENT!"
endlocal
