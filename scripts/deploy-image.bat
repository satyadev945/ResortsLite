@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: deploy-image.bat  -  Deploy ResortsLite to GCP GKE (Windows)
:: Usage: scripts\deploy-image.bat
:: Run from the repository root directory.
:: =============================================================================

set APP_NAME=resortslite
set NAMESPACE=resortslite

echo ==============================================
echo   ResortsLite - GKE Deployment
echo ==============================================

:: ---------- GCP / GKE credentials ----------
echo.
set /p GCP_PROJECT="GCP Project ID: "
set /p GCP_ZONE="GCP Zone (e.g. us-central1-a): "
set /p CLUSTER_NAME="GKE Cluster name: "

:: ---------- Image URI ----------
echo.
set /p IMAGE_URI="Full Docker image URI (e.g. us-central1-docker.pkg.dev/my-project/repo/resortslite:1.0.0): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Image URI cannot be empty.
    exit /b 1
)

:: ---------- Application environment variables ----------
echo.
echo --- Application Environment Variables ---
echo (Press Enter to use the default value)

set /p REDIS_HOST_VAL="REDIS_HOST (Google Cloud Memorystore IP/hostname) [localhost]: "
if "!REDIS_HOST_VAL!"=="" set REDIS_HOST_VAL=localhost

set /p REDIS_PORT_VAL="REDIS_PORT [6379]: "
if "!REDIS_PORT_VAL!"=="" set REDIS_PORT_VAL=6379

set /p REDIS_PASSWORD_VAL="REDIS_PASSWORD (leave blank if auth disabled): "

set /p PAYMENT_API_URL_VAL="PAYMENT_API_URL [http://payment-svc.payments.svc.cluster.local:9090/payments/charge]: "
if "!PAYMENT_API_URL_VAL!"=="" set PAYMENT_API_URL_VAL=http://payment-svc.payments.svc.cluster.local:9090/payments/charge

set /p REPORT_BASE_PATH_VAL="REPORT_BASE_PATH [/var/legacy/reports/]: "
if "!REPORT_BASE_PATH_VAL!"=="" set REPORT_BASE_PATH_VAL=/var/legacy/reports/

:: ---------- Configure kubectl ----------
echo.
echo Configuring kubectl for cluster: !CLUSTER_NAME! ...
gcloud container clusters get-credentials !CLUSTER_NAME! --zone !GCP_ZONE! --project !GCP_PROJECT!
if !ERRORLEVEL! neq 0 (echo ERROR: Failed to get cluster credentials & exit /b 1)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (echo ERROR: Cannot connect to cluster & exit /b 1)

:: ---------- Patch manifests (PowerShell sed equivalent) ----------
echo.
echo Patching Kubernetes manifests...

copy /Y kubernetes\deployment.yaml %TEMP%\deployment_patched.yaml >nul

powershell -Command "(Get-Content '%TEMP%\deployment_patched.yaml') -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content '%TEMP%\deployment_patched.yaml'"
powershell -Command "(Get-Content '%TEMP%\deployment_patched.yaml') -replace '{{REDIS_HOST}}', '!REDIS_HOST_VAL!' | Set-Content '%TEMP%\deployment_patched.yaml'"
powershell -Command "(Get-Content '%TEMP%\deployment_patched.yaml') -replace '{{REDIS_PORT}}', '!REDIS_PORT_VAL!' | Set-Content '%TEMP%\deployment_patched.yaml'"
powershell -Command "(Get-Content '%TEMP%\deployment_patched.yaml') -replace '{{REDIS_PASSWORD}}', '!REDIS_PASSWORD_VAL!' | Set-Content '%TEMP%\deployment_patched.yaml'"
powershell -Command "(Get-Content '%TEMP%\deployment_patched.yaml') -replace '{{PAYMENT_API_URL}}', '!PAYMENT_API_URL_VAL!' | Set-Content '%TEMP%\deployment_patched.yaml'"
powershell -Command "(Get-Content '%TEMP%\deployment_patched.yaml') -replace '{{REPORT_BASE_PATH}}', '!REPORT_BASE_PATH_VAL!' | Set-Content '%TEMP%\deployment_patched.yaml'"

:: ---------- Apply manifests ----------
echo.
echo Applying Kubernetes manifests...

echo   [1/4] Namespace...
kubectl apply -f kubernetes\namespace.yaml
if !ERRORLEVEL! neq 0 (echo ERROR: Failed to apply namespace & exit /b 1)

echo   [2/4] Deployment...
kubectl apply -f %TEMP%\deployment_patched.yaml
if !ERRORLEVEL! neq 0 (echo ERROR: Failed to apply deployment & exit /b 1)

echo   [3/4] Service...
kubectl apply -f kubernetes\service.yaml
if !ERRORLEVEL! neq 0 (echo ERROR: Failed to apply service & exit /b 1)

echo   [4/4] Ingress...
kubectl apply -f kubernetes\ingress.yaml
if !ERRORLEVEL! neq 0 (echo ERROR: Failed to apply ingress & exit /b 1)

:: ---------- Wait for rollout ----------
echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/%APP_NAME% -n %NAMESPACE% --timeout=300s
if !ERRORLEVEL! neq 0 (echo ERROR: Deployment rollout failed & exit /b 1)

:: ---------- Verify ----------
echo.
echo Deployed resources:
kubectl get pods,svc,ingress -n %NAMESPACE%

echo.
echo ==============================================
echo   Deployment complete!
echo ==============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/%APP_NAME% -n %NAMESPACE%

endlocal
