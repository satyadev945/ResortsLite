@echo off
setlocal enabledelayedexpansion

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "WORK_DIR=%TEMP%\%APP_NAME%-k8s-%RANDOM%"

set /p AWS_REGION="AWS region (e.g. us-east-1): "
set /p CLUSTER_NAME="EKS cluster name: "
set /p IMAGE_URI="Docker image URI (full path with tag): "
if "!AWS_REGION!"=="" ( echo Region is required & exit /b 1 )
if "!CLUSTER_NAME!"=="" ( echo Cluster name is required & exit /b 1 )
if "!IMAGE_URI!"=="" ( echo Image URI is required & exit /b 1 )

echo Application configuration (press Enter to skip or use default):
set /p SPRING_DATASOURCE_URL="SPRING_DATASOURCE_URL: "
set /p SPRING_DATASOURCE_USERNAME="SPRING_DATASOURCE_USERNAME: "
set /p SPRING_DATASOURCE_PASSWORD="SPRING_DATASOURCE_PASSWORD: "
set /p REDIS_HOST="REDIS_HOST [localhost]: "
if "!REDIS_HOST!"=="" set "REDIS_HOST=localhost"
set /p REDIS_PORT="REDIS_PORT [6379]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"
set /p REDIS_PASSWORD="REDIS_PASSWORD: "
set /p REDIS_SSL_ENABLED="REDIS_SSL_ENABLED [false]: "
if "!REDIS_SSL_ENABLED!"=="" set "REDIS_SSL_ENABLED=false"
set /p PAYMENT_API_URL="PAYMENT_API_URL [http://payment-svc:9090/payments/charge]: "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-svc:9090/payments/charge"
set /p APP_PAYMENT_ENDPOINT="APP_PAYMENT_ENDPOINT [http://payment-svc:9090/charge]: "
if "!APP_PAYMENT_ENDPOINT!"=="" set "APP_PAYMENT_ENDPOINT=http://payment-svc:9090/charge"
set /p APP_INVENTORY_ENDPOINT="APP_INVENTORY_ENDPOINT [http://inventory-svc:8081/rooms]: "
if "!APP_INVENTORY_ENDPOINT!"=="" set "APP_INVENTORY_ENDPOINT=http://inventory-svc:8081/rooms"
set /p APP_NOTIFICATION_ENDPOINT="APP_NOTIFICATION_ENDPOINT [http://notify:7070/send]: "
if "!APP_NOTIFICATION_ENDPOINT!"=="" set "APP_NOTIFICATION_ENDPOINT=http://notify:7070/send"

mkdir "!WORK_DIR!"
copy /y kubernetes\*.yaml "!WORK_DIR!\" >nul

rem Replace placeholders using PowerShell (values passed through environment variables)
powershell -NoProfile -Command "$f=Join-Path $env:WORK_DIR 'deployment.yaml'; $c=Get-Content -Raw $f; foreach($k in 'IMAGE_URI','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD','REDIS_SSL_ENABLED','PAYMENT_API_URL','APP_PAYMENT_ENDPOINT','APP_INVENTORY_ENDPOINT','APP_NOTIFICATION_ENDPOINT'){ $v=[Environment]::GetEnvironmentVariable($k); if($null -eq $v){$v=''}; $c=$c.Replace('{{'+$k+'}}',$v) }; Set-Content -NoNewline -Path $f -Value $c"
if !ERRORLEVEL! neq 0 (
    echo Failed to update manifests
    exit /b 1
)

echo Configuring kubectl for cluster !CLUSTER_NAME!...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
    echo Failed to configure kubectl
    exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo Cannot connect to cluster
    exit /b 1
)

echo Applying manifests...
kubectl apply -f "!WORK_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo namespace apply failed & exit /b 1 )
kubectl apply -f "!WORK_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 ( echo deployment apply failed & exit /b 1 )
kubectl apply -f "!WORK_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo service apply failed & exit /b 1 )
kubectl apply -f "!WORK_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ingress apply failed & exit /b 1 )

echo Waiting for rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo Rollout failed. To roll back run:
    echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

kubectl get pods,svc,ingress -n !NAMESPACE!
echo Ingress address:
kubectl get ingress resortslite-ingress -n !NAMESPACE! -o jsonpath="{.status.loadBalancer.ingress[0].hostname}"
echo.

rmdir /s /q "!WORK_DIR!"
endlocal
