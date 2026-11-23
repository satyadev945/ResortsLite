@echo off
setlocal enabledelayedexpansion

REM Run from the repository root.
set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"

set "AWS_REGION="
set "CLUSTER_NAME="
set "IMAGE_URI="
set /p AWS_REGION="Enter AWS region (e.g. us-east-1): "
set /p CLUSTER_NAME="Enter EKS cluster name: "
set /p IMAGE_URI="Enter Docker image URI (registry/repo:tag): "
if "!AWS_REGION!"=="" goto missing
if "!CLUSTER_NAME!"=="" goto missing
if "!IMAGE_URI!"=="" goto missing

echo --- Application configuration ---
set "SPRING_DATASOURCE_URL="
set "SPRING_DATASOURCE_USERNAME="
set "SPRING_DATASOURCE_PASSWORD="
set "REDIS_HOST="
set "REDIS_PORT="
set "REDIS_PASSWORD="
set "REDIS_SSL_ENABLED="
set "PAYMENT_API_URL="
set /p SPRING_DATASOURCE_URL="Enter value for SPRING_DATASOURCE_URL: "
set /p SPRING_DATASOURCE_USERNAME="Enter value for SPRING_DATASOURCE_USERNAME: "
set /p SPRING_DATASOURCE_PASSWORD="Enter value for SPRING_DATASOURCE_PASSWORD: "
set /p REDIS_HOST="Enter value for REDIS_HOST (ElastiCache endpoint): "
set /p REDIS_PORT="Enter value for REDIS_PORT (or press Enter for 6379): "
set /p REDIS_PASSWORD="Enter value for REDIS_PASSWORD (or press Enter to skip): "
set /p REDIS_SSL_ENABLED="Enter value for REDIS_SSL_ENABLED (true/false, Enter for false): "
set /p PAYMENT_API_URL="Enter value for PAYMENT_API_URL (or press Enter for default): "
if "!SPRING_DATASOURCE_URL!"=="" goto missing
if "!SPRING_DATASOURCE_USERNAME!"=="" goto missing
if "!SPRING_DATASOURCE_PASSWORD!"=="" goto missing
if "!REDIS_HOST!"=="" goto missing
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"
if "!REDIS_SSL_ENABLED!"=="" set "REDIS_SSL_ENABLED=false"
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-svc:9090/payments/charge"

REM Work on a temp copy so the template manifests stay untouched
set "WORK_DIR=%TEMP%\resortslite-deploy"
if exist "!WORK_DIR!" rmdir /s /q "!WORK_DIR!"
mkdir "!WORK_DIR!\kubernetes"
xcopy /e /i /y /q kubernetes "!WORK_DIR!\kubernetes" >nul
if !ERRORLEVEL! neq 0 (
  echo Failed to copy manifests
  exit /b 1
)

powershell -NoProfile -Command "Get-ChildItem -Path (Join-Path $env:WORK_DIR 'kubernetes') -Filter *.yaml | ForEach-Object { $c = [System.IO.File]::ReadAllText($_.FullName); $c = $c.Replace('{{IMAGE_URI}}',$env:IMAGE_URI).Replace('{{NAMESPACE}}',$env:NAMESPACE).Replace('{{SPRING_DATASOURCE_URL}}',$env:SPRING_DATASOURCE_URL).Replace('{{SPRING_DATASOURCE_USERNAME}}',$env:SPRING_DATASOURCE_USERNAME).Replace('{{SPRING_DATASOURCE_PASSWORD}}',$env:SPRING_DATASOURCE_PASSWORD).Replace('{{REDIS_HOST}}',$env:REDIS_HOST).Replace('{{REDIS_PORT}}',$env:REDIS_PORT).Replace('{{REDIS_PASSWORD}}',$env:REDIS_PASSWORD).Replace('{{REDIS_SSL_ENABLED}}',$env:REDIS_SSL_ENABLED).Replace('{{PAYMENT_API_URL}}',$env:PAYMENT_API_URL); [System.IO.File]::WriteAllText($_.FullName, $c) }"
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
kubectl apply -f "!WORK_DIR!\kubernetes\namespace.yaml"
if !ERRORLEVEL! neq 0 exit /b 1
kubectl apply -f "!WORK_DIR!\kubernetes\secret.yaml"
if !ERRORLEVEL! neq 0 exit /b 1
kubectl apply -f "!WORK_DIR!\kubernetes\deployment.yaml"
if !ERRORLEVEL! neq 0 exit /b 1
kubectl apply -f "!WORK_DIR!\kubernetes\service.yaml"
if !ERRORLEVEL! neq 0 exit /b 1
kubectl apply -f "!WORK_DIR!\kubernetes\ingress.yaml"
if !ERRORLEVEL! neq 0 exit /b 1

echo Waiting for rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
  echo Rollout failed. Inspect with: kubectl describe pods -n !NAMESPACE!
  echo Roll back with: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
  exit /b 1
)

kubectl get pods,svc,ingress -n !NAMESPACE!

echo Ingress address - run: kubectl get ingress !APP_NAME!-ingress -n !NAMESPACE!
echo Health URL: http://INGRESS_ADDRESS/actuator/health
echo Rollback if needed: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

rmdir /s /q "!WORK_DIR!" >nul 2>&1
endlocal
exit /b 0

:missing
echo All required values must be provided.
exit /b 1
