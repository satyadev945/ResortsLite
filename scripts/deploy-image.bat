@echo off
setlocal enabledelayedexpansion

rem Run from repository root: scripts\deploy-image.bat
set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "SECRET_NAME=resortslite-secrets"

where az >nul 2>&1
if !ERRORLEVEL! neq 0 (
    echo ERROR: Azure CLI is required but not installed
    exit /b 1
)
where kubectl >nul 2>&1
if !ERRORLEVEL! neq 0 (
    echo ERROR: kubectl is required but not installed
    exit /b 1
)

set "RESOURCE_GROUP="
set /p RESOURCE_GROUP="Enter Azure resource group: "
if "!RESOURCE_GROUP!"=="" (
    echo Resource group is required
    exit /b 1
)
set "CLUSTER_NAME="
set /p CLUSTER_NAME="Enter AKS cluster name: "
if "!CLUSTER_NAME!"=="" (
    echo AKS cluster name is required
    exit /b 1
)
set "IMAGE_URI="
set /p IMAGE_URI="Enter Docker image URI (e.g. myacr.azurecr.io/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo Image URI is required
    exit /b 1
)
set "INGRESS_HOST="
set /p INGRESS_HOST="Enter ingress host [resortslite.example.com]: "
if "!INGRESS_HOST!"=="" set "INGRESS_HOST=resortslite.example.com"

echo --- Application configuration (press Enter to skip/use default) ---
set "DB_URL="
set /p DB_URL="Enter value for SPRING_DATASOURCE_URL (or press Enter to skip): "
set "DB_USERNAME="
set /p DB_USERNAME="Enter value for SPRING_DATASOURCE_USERNAME (or press Enter to skip): "
set "DB_PASSWORD="
set /p DB_PASSWORD="Enter value for SPRING_DATASOURCE_PASSWORD (or press Enter to skip): "
set "REDIS_HOST="
set /p REDIS_HOST="Enter value for REDIS_HOST [localhost]: "
if "!REDIS_HOST!"=="" set "REDIS_HOST=localhost"
set "REDIS_PORT="
set /p REDIS_PORT="Enter value for REDIS_PORT [6380]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6380"
set "REDIS_PASSWORD="
set /p REDIS_PASSWORD="Enter value for REDIS_PASSWORD (or press Enter to skip): "
set "REDIS_SSL_ENABLED="
set /p REDIS_SSL_ENABLED="Enter value for REDIS_SSL_ENABLED [true]: "
if "!REDIS_SSL_ENABLED!"=="" set "REDIS_SSL_ENABLED=true"
set "PAYMENT_API_URL="
set /p PAYMENT_API_URL="Enter value for PAYMENT_API_URL (or press Enter for default): "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-svc.internal:9090/payments/charge"

rem Work on a copy so template manifests keep their placeholders
set "WORK_DIR=%TEMP%\resortslite-k8s-%RANDOM%"
mkdir "!WORK_DIR!"
copy /y kubernetes\*.yaml "!WORK_DIR!\" >nul
if !ERRORLEVEL! neq 0 (
    echo Failed to copy manifests. Run this script from the repository root.
    exit /b 1
)

set "REPL_KEY={{IMAGE_URI}}"
set "REPL_VAL=!IMAGE_URI!"
call :replace
set "REPL_KEY={{NAMESPACE}}"
set "REPL_VAL=!NAMESPACE!"
call :replace
set "REPL_KEY={{INGRESS_HOST}}"
set "REPL_VAL=!INGRESS_HOST!"
call :replace
set "REPL_KEY={{REDIS_HOST}}"
set "REPL_VAL=!REDIS_HOST!"
call :replace
set "REPL_KEY={{REDIS_PORT}}"
set "REPL_VAL=!REDIS_PORT!"
call :replace
set "REPL_KEY={{REDIS_SSL_ENABLED}}"
set "REPL_VAL=!REDIS_SSL_ENABLED!"
call :replace
set "REPL_KEY={{PAYMENT_API_URL}}"
set "REPL_VAL=!PAYMENT_API_URL!"
call :replace

echo Configuring kubectl for AKS...
az aks get-credentials --resource-group "!RESOURCE_GROUP!" --name "!CLUSTER_NAME!" --overwrite-existing
if !ERRORLEVEL! neq 0 (
    echo Failed to get AKS credentials
    exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo Cannot reach cluster
    exit /b 1
)

echo Applying namespace...
kubectl apply -f "!WORK_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 (
    echo Failed to apply namespace
    exit /b 1
)

set "SECRET_ARGS="
if not "!DB_URL!"=="" set SECRET_ARGS=!SECRET_ARGS! "--from-literal=SPRING_DATASOURCE_URL=!DB_URL!"
if not "!DB_USERNAME!"=="" set SECRET_ARGS=!SECRET_ARGS! "--from-literal=SPRING_DATASOURCE_USERNAME=!DB_USERNAME!"
if not "!DB_PASSWORD!"=="" set SECRET_ARGS=!SECRET_ARGS! "--from-literal=SPRING_DATASOURCE_PASSWORD=!DB_PASSWORD!"
if not "!REDIS_PASSWORD!"=="" set SECRET_ARGS=!SECRET_ARGS! "--from-literal=REDIS_PASSWORD=!REDIS_PASSWORD!"

if defined SECRET_ARGS (
    echo Creating/updating secret !SECRET_NAME!...
    kubectl create secret generic !SECRET_NAME! -n !NAMESPACE! !SECRET_ARGS! --dry-run=client -o yaml > "!WORK_DIR!\secret.generated"
    if !ERRORLEVEL! neq 0 (
        echo Failed to generate secret
        exit /b 1
    )
    kubectl apply -f "!WORK_DIR!\secret.generated"
    if !ERRORLEVEL! neq 0 (
        echo Failed to apply secret
        exit /b 1
    )
) else (
    echo No secret values supplied; application defaults will be used.
)

echo Applying deployment, service, ingress...
kubectl apply -f "!WORK_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 (
    echo Failed to apply deployment
    exit /b 1
)
kubectl apply -f "!WORK_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 (
    echo Failed to apply service
    exit /b 1
)
kubectl apply -f "!WORK_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 (
    echo Failed to apply ingress
    exit /b 1
)

echo Waiting for rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: rollout failed. To roll back run:
    echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    echo Inspect with: kubectl describe pods -n !NAMESPACE!
    exit /b 1
)

kubectl get pods,svc,ingress -n !NAMESPACE!

rmdir /s /q "!WORK_DIR!" >nul 2>&1
echo Application URL: http://!INGRESS_HOST!/
echo Health: http://!INGRESS_HOST!/actuator/health
endlocal
exit /b 0

:replace
set "TARGET_DIR=!WORK_DIR!"
powershell -NoProfile -Command "Get-ChildItem -Path $env:TARGET_DIR -Filter *.yaml | ForEach-Object { $c=[IO.File]::ReadAllText($_.FullName); $c=$c.Replace($env:REPL_KEY,$env:REPL_VAL); [IO.File]::WriteAllText($_.FullName,$c) }"
exit /b 0
