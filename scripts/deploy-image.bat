@echo off
setlocal enabledelayedexpansion

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"

echo === Deploy %APP_NAME% to Azure AKS ===

where az >nul 2>&1
if !ERRORLEVEL! neq 0 (
  echo ERROR: Azure CLI ^(az^) is required
  exit /b 1
)
where kubectl >nul 2>&1
if !ERRORLEVEL! neq 0 (
  echo ERROR: kubectl is required
  exit /b 1
)
if not exist kubernetes (
  echo ERROR: kubernetes directory not found. Run from the repository root.
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
set /p IMAGE_URI="Enter Docker image URI (e.g. myacr.azurecr.io/resortslite:latest): "
if "!IMAGE_URI!"=="" (
  echo ERROR: image URI is required
  exit /b 1
)

echo.
echo Application configuration - press Enter to use the default
set /p SPRING_DATASOURCE_URL="SPRING_DATASOURCE_URL [default: existing SQL Server URL]: "
if "!SPRING_DATASOURCE_URL!"=="" set "SPRING_DATASOURCE_URL=jdbc:sqlserver://db-prod.resorts-internal.com:1433;databaseName=ResortsDB;encrypt=false;trustServerCertificate=true"
set /p SPRING_DATASOURCE_USERNAME="SPRING_DATASOURCE_USERNAME [admin]: "
if "!SPRING_DATASOURCE_USERNAME!"=="" set "SPRING_DATASOURCE_USERNAME=admin"
set /p SPRING_DATASOURCE_PASSWORD="SPRING_DATASOURCE_PASSWORD: "
set /p REDIS_HOST="REDIS_HOST [localhost]: "
if "!REDIS_HOST!"=="" set "REDIS_HOST=localhost"
set /p REDIS_PORT="REDIS_PORT [6380]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6380"
set /p REDIS_SSL_ENABLED="REDIS_SSL_ENABLED [true]: "
if "!REDIS_SSL_ENABLED!"=="" set "REDIS_SSL_ENABLED=true"
set /p REDIS_PASSWORD="REDIS_PASSWORD: "
set /p BOOKING_CACHE_TTL_SECONDS="BOOKING_CACHE_TTL_SECONDS [3600]: "
if "!BOOKING_CACHE_TTL_SECONDS!"=="" set "BOOKING_CACHE_TTL_SECONDS=3600"
set /p PAYMENT_API_URL="PAYMENT_API_URL [http://payment-svc.internal:9090/payments/charge]: "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-svc.internal:9090/payments/charge"

set "WORK_DIR=%TEMP%\resortslite-k8s-%RANDOM%"
mkdir "!WORK_DIR!"
xcopy /E /I /Y /Q kubernetes "!WORK_DIR!" >nul
if !ERRORLEVEL! neq 0 (
  echo ERROR: failed to copy manifests
  exit /b 1
)

REM Replace placeholders (PowerShell reads values from environment variables)
powershell -NoProfile -Command "Get-ChildItem -Path $env:WORK_DIR -Filter *.yaml | ForEach-Object { $c = Get-Content $_.FullName -Raw; $c = $c.Replace('{{IMAGE_URI}}',$env:IMAGE_URI).Replace('{{NAMESPACE}}',$env:NAMESPACE).Replace('{{SPRING_DATASOURCE_URL}}',$env:SPRING_DATASOURCE_URL).Replace('{{REDIS_HOST}}',$env:REDIS_HOST).Replace('{{REDIS_PORT}}',$env:REDIS_PORT).Replace('{{REDIS_SSL_ENABLED}}',$env:REDIS_SSL_ENABLED).Replace('{{BOOKING_CACHE_TTL_SECONDS}}',$env:BOOKING_CACHE_TTL_SECONDS).Replace('{{PAYMENT_API_URL}}',$env:PAYMENT_API_URL); Set-Content -Path $_.FullName -Value $c -NoNewline }"
if !ERRORLEVEL! neq 0 (
  echo ERROR: failed to update manifests
  exit /b 1
)

echo Configuring kubectl...
az aks get-credentials --resource-group !RESOURCE_GROUP! --name !CLUSTER_NAME! --overwrite-existing
if !ERRORLEVEL! neq 0 (
  echo ERROR: failed to get AKS credentials
  exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
  echo ERROR: cannot reach cluster
  exit /b 1
)

echo Applying namespace...
kubectl apply -f "!WORK_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 exit /b 1

echo Creating/updating secret resortslite-secrets...
kubectl create secret generic resortslite-secrets -n !NAMESPACE! --from-literal=SPRING_DATASOURCE_USERNAME="!SPRING_DATASOURCE_USERNAME!" --from-literal=SPRING_DATASOURCE_PASSWORD="!SPRING_DATASOURCE_PASSWORD!" --from-literal=REDIS_PASSWORD="!REDIS_PASSWORD!" --dry-run=client -o yaml > "!WORK_DIR!\secret.yaml"
if !ERRORLEVEL! neq 0 (
  echo ERROR: failed to render secret
  exit /b 1
)
kubectl apply -f "!WORK_DIR!\secret.yaml"
if !ERRORLEVEL! neq 0 exit /b 1

echo Applying deployment...
kubectl apply -f "!WORK_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 exit /b 1
echo Applying service...
kubectl apply -f "!WORK_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 exit /b 1
echo Applying ingress...
kubectl apply -f "!WORK_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 exit /b 1

echo Waiting for rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
  echo ERROR: rollout failed.
  echo Rollback with: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
  echo Inspect with: kubectl describe pods -n !NAMESPACE!
  rmdir /S /Q "!WORK_DIR!"
  exit /b 1
)

kubectl get pods,svc,ingress -n !NAMESPACE!
echo Ingress address: kubectl get ingress resortslite-ingress -n !NAMESPACE!
echo Host header: resortslite.example.com
echo Rollback if needed: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

rmdir /S /Q "!WORK_DIR!"
endlocal
