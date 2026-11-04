@echo off
setlocal enabledelayedexpansion

:: =============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: Usage: scripts\deploy-image.bat
:: Run from repository root directory
:: Prerequisites: aws-cli, kubectl
:: =============================================================

set "APP_NAME=resortsLite"
set "NAMESPACE=resortsLite"
set "K8S_DIR=kubernetes"

echo ==============================================
echo   ResortsLite -- Deploy to AWS EKS
echo ==============================================

:: ---- Collect deployment inputs ----
set /p "AWS_REGION=Enter AWS region (e.g. us-east-1): "
if "!AWS_REGION!"=="" (
    echo ERROR: AWS region is required.
    exit /b 1
)

set /p "CLUSTER_NAME=Enter EKS cluster name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS cluster name is required.
    exit /b 1
)

set /p "IMAGE_URI=Enter full Docker image URI: "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

echo.
echo --- Application Environment Variables ---
echo Press Enter to skip any variable.

set /p "SPRING_DATASOURCE_URL=Enter SPRING_DATASOURCE_URL (Oracle JDBC URL): "
set /p "PAYMENT_API_URL=Enter PAYMENT_API_URL [http://payment-service.default.svc.cluster.local:9090/payments/charge]: "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-service.default.svc.cluster.local:9090/payments/charge"

set /p "REPORT_BASE_PATH=Enter REPORT_BASE_PATH [/var/reports]: "
if "!REPORT_BASE_PATH!"=="" set "REPORT_BASE_PATH=/var/reports"

set /p "BACKUP_PATH=Enter BACKUP_PATH [/var/backups/nightly]: "
if "!BACKUP_PATH!"=="" set "BACKUP_PATH=/var/backups/nightly"

:: ---- Configure kubectl for EKS ----
echo.
echo Configuring kubectl for EKS cluster: !CLUSTER_NAME! in !AWS_REGION!...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to configure kubectl.
    exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to EKS cluster.
    exit /b 1
)

:: ---- Substitute placeholders using PowerShell ----
echo.
echo Updating Kubernetes manifests with deployment values...

copy "!K8S_DIR!\deployment.yaml" "!K8S_DIR!\deployment.yaml.bak" >nul

powershell -NoProfile -Command ^
  "(Get-Content '!K8S_DIR!\deployment.yaml') ^
   -replace '{{IMAGE_URI}}','!IMAGE_URI!' ^
   -replace '{{SPRING_DATASOURCE_URL}}','!SPRING_DATASOURCE_URL!' ^
   -replace '{{PAYMENT_API_URL}}','!PAYMENT_API_URL!' ^
   -replace '{{REPORT_BASE_PATH}}','!REPORT_BASE_PATH!' ^
   -replace '{{BACKUP_PATH}}','!BACKUP_PATH!' ^
   | Set-Content '!K8S_DIR!\deployment.yaml'"

if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to update deployment manifest.
    copy "!K8S_DIR!\deployment.yaml.bak" "!K8S_DIR!\deployment.yaml" >nul
    exit /b 1
)

:: ---- Apply manifests in order ----
echo.
echo Applying Kubernetes manifests...

echo   [1/4] Applying namespace...
kubectl apply -f "!K8S_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & goto :restore )

echo   [2/4] Applying deployment...
kubectl apply -f "!K8S_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & goto :restore )

echo   [3/4] Applying service...
kubectl apply -f "!K8S_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & goto :restore )

echo   [4/4] Applying ingress...
kubectl apply -f "!K8S_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & goto :restore )

:: ---- Restore original deployment.yaml ----
:restore
copy "!K8S_DIR!\deployment.yaml.bak" "!K8S_DIR!\deployment.yaml" >nul
del "!K8S_DIR!\deployment.yaml.bak" >nul 2>&1

:: ---- Wait for rollout ----
echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo WARNING: Rollout did not complete within timeout.
    echo Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

:: ---- Verify resources ----
echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ==============================================
echo   Deployment Complete!
echo   Namespace: !NAMESPACE!
echo   Image:     !IMAGE_URI!
echo ==============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

endlocal
exit /b 0
