@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: Usage: scripts\deploy-image.bat
:: Run from repository root directory
:: ============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "K8S_DIR=kubernetes"
set "TMP_DIR=%TEMP%\resortslite-k8s-deploy"

echo ==============================================
echo   ResortsLite - AWS EKS Deployment Script
echo ==============================================
echo.

:: ── AWS / EKS configuration ──────────────────────────────────
set /p "AWS_REGION=Enter AWS region [us-east-1]: "
if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

set /p "CLUSTER_NAME=Enter EKS cluster name: "
if "!CLUSTER_NAME!"=="" (
  echo ERROR: EKS cluster name is required.
  exit /b 1
)

:: ── Docker image URI ─────────────────────────────────────────
set /p "IMAGE_URI=Enter full Docker image URI: "
if "!IMAGE_URI!"=="" (
  echo ERROR: Docker image URI is required.
  exit /b 1
)

echo.
echo -- Application Environment Variables --
echo   (Press Enter to use default values)
echo.

set /p "SPRING_DATASOURCE_URL=Enter SPRING_DATASOURCE_URL [jdbc:oracle:thin:@db-host:1521:ORCL]: "
if "!SPRING_DATASOURCE_URL!"=="" set "SPRING_DATASOURCE_URL=jdbc:oracle:thin:@db-host:1521:ORCL"

set /p "REDIS_HOST=Enter REDIS_HOST [localhost]: "
if "!REDIS_HOST!"=="" set "REDIS_HOST=localhost"

set /p "REDIS_PORT=Enter REDIS_PORT [6379]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"

set /p "REDIS_SSL=Enter REDIS_SSL [false]: "
if "!REDIS_SSL!"=="" set "REDIS_SSL=false"

set /p "PAYMENT_API_URL=Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-service:9090/payments/charge"

set /p "APP_INVENTORY_ENDPOINT=Enter APP_INVENTORY_ENDPOINT [http://inventory-service:8081/rooms]: "
if "!APP_INVENTORY_ENDPOINT!"=="" set "APP_INVENTORY_ENDPOINT=http://inventory-service:8081/rooms"

set /p "APP_NOTIFICATION_ENDPOINT=Enter APP_NOTIFICATION_ENDPOINT [http://notify-service:7070/send]: "
if "!APP_NOTIFICATION_ENDPOINT!"=="" set "APP_NOTIFICATION_ENDPOINT=http://notify-service:7070/send"

echo.
echo -- Configuring kubectl for EKS --
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
  echo ERROR: Failed to configure kubectl for EKS cluster '!CLUSTER_NAME!'.
  exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
  echo ERROR: Cannot connect to EKS cluster.
  exit /b 1
)
echo.

:: ── Copy manifests to temp directory ─────────────────────────
if exist "!TMP_DIR!" rmdir /s /q "!TMP_DIR!"
xcopy /e /i /q "!K8S_DIR!" "!TMP_DIR!" >nul
if !ERRORLEVEL! neq 0 (
  echo ERROR: Failed to copy Kubernetes manifests.
  exit /b 1
)

:: ── Substitute placeholders using PowerShell ─────────────────
echo -- Updating Kubernetes manifests --

set "DEPLOY_FILE=!TMP_DIR!\deployment.yaml"

powershell -Command "(Get-Content '!DEPLOY_FILE!') -replace '\{\{IMAGE_URI\}\}', '!IMAGE_URI!' | Set-Content '!DEPLOY_FILE!'"
powershell -Command "(Get-Content '!DEPLOY_FILE!') -replace '\{\{SPRING_DATASOURCE_URL\}\}', '!SPRING_DATASOURCE_URL!' | Set-Content '!DEPLOY_FILE!'"
powershell -Command "(Get-Content '!DEPLOY_FILE!') -replace '\{\{REDIS_HOST\}\}', '!REDIS_HOST!' | Set-Content '!DEPLOY_FILE!'"
powershell -Command "(Get-Content '!DEPLOY_FILE!') -replace '\{\{REDIS_PORT\}\}', '!REDIS_PORT!' | Set-Content '!DEPLOY_FILE!'"
powershell -Command "(Get-Content '!DEPLOY_FILE!') -replace '\{\{REDIS_SSL\}\}', '!REDIS_SSL!' | Set-Content '!DEPLOY_FILE!'"
powershell -Command "(Get-Content '!DEPLOY_FILE!') -replace '\{\{PAYMENT_API_URL\}\}', '!PAYMENT_API_URL!' | Set-Content '!DEPLOY_FILE!'"
powershell -Command "(Get-Content '!DEPLOY_FILE!') -replace '\{\{APP_INVENTORY_ENDPOINT\}\}', '!APP_INVENTORY_ENDPOINT!' | Set-Content '!DEPLOY_FILE!'"
powershell -Command "(Get-Content '!DEPLOY_FILE!') -replace '\{\{APP_NOTIFICATION_ENDPOINT\}\}', '!APP_NOTIFICATION_ENDPOINT!' | Set-Content '!DEPLOY_FILE!'"

echo Manifests updated.
echo.

:: ── Apply manifests in order ─────────────────────────────────
echo -- Applying Kubernetes manifests --

echo [1/4] Applying namespace...
kubectl apply -f "!TMP_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 (
  echo ERROR: Failed to apply namespace.
  exit /b 1
)

echo [2/4] Applying deployment...
kubectl apply -f "!TMP_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 (
  echo ERROR: Failed to apply deployment.
  exit /b 1
)

echo [3/4] Applying service...
kubectl apply -f "!TMP_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 (
  echo ERROR: Failed to apply service.
  exit /b 1
)

echo [4/4] Applying ingress...
kubectl apply -f "!TMP_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 (
  echo ERROR: Failed to apply ingress.
  exit /b 1
)

echo.

:: ── Wait for rollout ─────────────────────────────────────────
echo -- Waiting for deployment rollout --
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
  echo.
  echo ERROR: Deployment rollout failed. Initiating rollback...
  kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
  echo Rollback initiated. Check pod logs:
  echo   kubectl logs -l app=!APP_NAME! -n !NAMESPACE! --tail=50
  exit /b 1
)

echo.

:: ── Verify resources ─────────────────────────────────────────
echo -- Verifying deployed resources --
kubectl get pods,svc,ingress -n !NAMESPACE!
echo.

:: Cleanup temp files
rmdir /s /q "!TMP_DIR!" >nul 2>&1

echo ==============================================
echo   Deployment completed successfully!
echo ==============================================
echo.
echo Useful commands:
echo   kubectl get pods -n !NAMESPACE!
echo   kubectl logs -l app=!APP_NAME! -n !NAMESPACE! --tail=100
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

endlocal
exit /b 0
