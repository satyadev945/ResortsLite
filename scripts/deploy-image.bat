@echo off
setlocal enabledelayedexpansion

set "APP_NAME=java-msql"
set "NAMESPACE=java-msql"

echo === Deploy !APP_NAME! to AWS EKS ===

set /p AWS_REGION="AWS region (e.g. us-east-1): "
set /p CLUSTER_NAME="EKS cluster name: "
set /p IMAGE_URI="Docker image URI (registry/repo:tag): "
if "!AWS_REGION!"=="" (echo Region is required & exit /b 1)
if "!CLUSTER_NAME!"=="" (echo Cluster name is required & exit /b 1)
if "!IMAGE_URI!"=="" (echo Image URI is required & exit /b 1)

set "V_DB_URL="
set "V_DB_USER="
set "V_DB_PASS="
set "V_REDIS_HOST="
set "V_REDIS_PORT="
set "V_REDIS_PASS="
set "V_REDIS_SSL="
set "V_PAYMENT="
set "V_INVENTORY="
set "V_NOTIFY="
set /p V_DB_URL="Enter value for SPRING_DATASOURCE_URL (or press Enter to skip): "
set /p V_DB_USER="Enter value for SPRING_DATASOURCE_USERNAME (or press Enter to skip): "
set /p V_DB_PASS="Enter value for SPRING_DATASOURCE_PASSWORD (or press Enter to skip): "
set /p V_REDIS_HOST="Enter value for REDIS_HOST (or press Enter to skip): "
set /p V_REDIS_PORT="Enter value for REDIS_PORT (or press Enter to skip): "
set /p V_REDIS_PASS="Enter value for REDIS_PASSWORD (or press Enter to skip): "
set /p V_REDIS_SSL="Enter value for REDIS_SSL_ENABLED (or press Enter to skip): "
set /p V_PAYMENT="Enter value for PAYMENT_API_URL (or press Enter to skip): "
set /p V_INVENTORY="Enter value for APP_INVENTORY_ENDPOINT (or press Enter to skip): "
set /p V_NOTIFY="Enter value for APP_NOTIFICATION_ENDPOINT (or press Enter to skip): "

if "!V_DB_URL!"=="" set "V_DB_URL=jdbc:sqlserver://db-prod.resorts-internal.com:1433;databaseName=ResortsDB;encrypt=false;trustServerCertificate=true"
if "!V_DB_USER!"=="" set "V_DB_USER=admin"
if "!V_REDIS_HOST!"=="" set "V_REDIS_HOST=localhost"
if "!V_REDIS_PORT!"=="" set "V_REDIS_PORT=6379"
if "!V_REDIS_SSL!"=="" set "V_REDIS_SSL=false"
if "!V_PAYMENT!"=="" set "V_PAYMENT=http://payment-svc:9090/payments/charge"
if "!V_INVENTORY!"=="" set "V_INVENTORY=http://inventory-svc.internal:8081/rooms"
if "!V_NOTIFY!"=="" set "V_NOTIFY=http://notify.internal:7070/send"

set "WORK_DIR=%TEMP%\!APP_NAME!-k8s-%RANDOM%"
mkdir "!WORK_DIR!"
copy /y kubernetes\*.yaml "!WORK_DIR!\" >nul

rem Substitute placeholders via PowerShell (literal replacement, values passed through environment)
set "P_IMAGE_URI=!IMAGE_URI!"
set "P_DB_URL=!V_DB_URL!"
set "P_DB_USER=!V_DB_USER!"
set "P_REDIS_HOST=!V_REDIS_HOST!"
set "P_REDIS_PORT=!V_REDIS_PORT!"
set "P_REDIS_SSL=!V_REDIS_SSL!"
set "P_PAYMENT=!V_PAYMENT!"
set "P_INVENTORY=!V_INVENTORY!"
set "P_NOTIFY=!V_NOTIFY!"
set "P_FILE=!WORK_DIR!\deployment.yaml"
powershell -NoProfile -Command "$f=$env:P_FILE; $c=Get-Content -Raw $f; $c=$c.Replace('{{IMAGE_URI}}',$env:P_IMAGE_URI).Replace('{{SPRING_DATASOURCE_URL}}',$env:P_DB_URL).Replace('{{SPRING_DATASOURCE_USERNAME}}',$env:P_DB_USER).Replace('{{REDIS_HOST}}',$env:P_REDIS_HOST).Replace('{{REDIS_PORT}}',$env:P_REDIS_PORT).Replace('{{REDIS_SSL_ENABLED}}',$env:P_REDIS_SSL).Replace('{{PAYMENT_API_URL}}',$env:P_PAYMENT).Replace('{{APP_INVENTORY_ENDPOINT}}',$env:P_INVENTORY).Replace('{{APP_NOTIFICATION_ENDPOINT}}',$env:P_NOTIFY); Set-Content -NoNewline -Path $f -Value $c"
if !ERRORLEVEL! neq 0 (
  echo Failed to update manifests
  exit /b 1
)

echo Configuring kubectl...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
  echo Failed to configure kubectl
  exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
  echo Cannot reach cluster
  exit /b 1
)

echo Applying manifests...
kubectl apply -f "!WORK_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 exit /b 1

kubectl create secret generic !APP_NAME!-secrets -n !NAMESPACE! "--from-literal=db-password=!V_DB_PASS!" "--from-literal=redis-password=!V_REDIS_PASS!" --dry-run=client -o yaml > "!WORK_DIR!\secret.yaml"
if !ERRORLEVEL! neq 0 (
  echo Failed to build secret
  exit /b 1
)
kubectl apply -f "!WORK_DIR!\secret.yaml"
if !ERRORLEVEL! neq 0 exit /b 1

kubectl apply -f "!WORK_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 exit /b 1
kubectl apply -f "!WORK_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 exit /b 1
kubectl apply -f "!WORK_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 exit /b 1

echo Waiting for rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
  echo Rollout failed. To roll back run:
  echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
  exit /b 1
)

kubectl get pods,svc,ingress -n !NAMESPACE!

echo Ingress address:
kubectl get ingress !APP_NAME!-ingress -n !NAMESPACE! -o jsonpath="{.status.loadBalancer.ingress[0].hostname}"
echo.
echo Rollback if needed: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

rmdir /s /q "!WORK_DIR!"
endlocal
