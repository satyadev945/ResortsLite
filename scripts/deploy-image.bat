@echo off
setlocal enabledelayedexpansion

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "K8S_DIR=%~dp0..\kubernetes"

echo === ResortsLite - Deploy to AWS EKS ===

set "AWS_REGION="
set /p AWS_REGION="AWS region, for example us-east-1: "
if "!AWS_REGION!"=="" (
  echo AWS region is required
  exit /b 1
)
set "CLUSTER_NAME="
set /p CLUSTER_NAME="EKS cluster name: "
if "!CLUSTER_NAME!"=="" (
  echo EKS cluster name is required
  exit /b 1
)
set "IMAGE_URI="
set /p IMAGE_URI="Docker image URI, full path with tag: "
if "!IMAGE_URI!"=="" (
  echo Image URI is required
  exit /b 1
)

echo.
echo Optional application configuration. Press Enter to skip any value.
set "CM_ARGS="
set "SECRET_ARGS="
call :ask_cfg SPRING_DATASOURCE_URL
call :ask_cfg SPRING_DATASOURCE_USERNAME
call :ask_secret SPRING_DATASOURCE_PASSWORD
call :ask_cfg REDIS_HOST
call :ask_cfg REDIS_PORT
call :ask_cfg REDIS_SSL_ENABLED
call :ask_secret REDIS_PASSWORD
call :ask_cfg APP_PAYMENT_ENDPOINT
call :ask_cfg APP_INVENTORY_ENDPOINT
call :ask_cfg APP_NOTIFICATION_ENDPOINT

rem Work on a temporary copy so repository manifests keep their placeholders
set "WORK_DIR=%TEMP%\resortslite-k8s-%RANDOM%"
mkdir "!WORK_DIR!"
copy /y "!K8S_DIR!\*.yaml" "!WORK_DIR!\" >nul
if !ERRORLEVEL! neq 0 (
  echo Failed to copy manifests
  exit /b 1
)

powershell -NoProfile -Command "$p = Join-Path $env:WORK_DIR 'deployment.yaml'; (Get-Content -Raw $p).Replace('{{IMAGE_URI}}', $env:IMAGE_URI).Replace('{{NAMESPACE}}', $env:NAMESPACE) | Set-Content -NoNewline $p"
if !ERRORLEVEL! neq 0 (
  echo Failed to update deployment manifest
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

echo Applying namespace...
kubectl apply -f "!WORK_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 exit /b 1

if defined CM_ARGS (
  echo Applying ConfigMap !APP_NAME!-config...
  kubectl create configmap !APP_NAME!-config -n !NAMESPACE! !CM_ARGS! --dry-run=client -o yaml | kubectl apply -f -
  if !ERRORLEVEL! neq 0 (
    echo ConfigMap apply failed
    exit /b 1
  )
)
if defined SECRET_ARGS (
  echo Applying Secret !APP_NAME!-secret...
  kubectl create secret generic !APP_NAME!-secret -n !NAMESPACE! !SECRET_ARGS! --dry-run=client -o yaml | kubectl apply -f -
  if !ERRORLEVEL! neq 0 (
    echo Secret apply failed
    exit /b 1
  )
)

echo Applying deployment, service, ingress...
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
echo.
echo Get the application URL with:
echo   kubectl get ingress !APP_NAME!-ingress -n !NAMESPACE!
echo Health endpoint path: /actuator/health
echo Rollback if needed: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

rmdir /s /q "!WORK_DIR!" >nul 2>&1
endlocal
exit /b 0

:ask_cfg
set "VAL="
set /p VAL="Enter value for %~1 or press Enter to skip: "
if defined VAL set "CM_ARGS=!CM_ARGS! --from-literal=%~1=!VAL!"
exit /b 0

:ask_secret
set "VAL="
set /p VAL="Enter value for %~1 or press Enter to skip: "
if defined VAL set "SECRET_ARGS=!SECRET_ARGS! --from-literal=%~1=!VAL!"
exit /b 0
