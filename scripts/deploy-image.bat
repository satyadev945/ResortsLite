@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy Gitpul Resort MonoCMP to AWS EKS
:: ============================================================

set "APP_NAME=gitpul-resort-monocmp"
set "NAMESPACE=gitpul-resort-monocmp"
set "K8S_DIR=kubernetes"

echo ==============================================
echo   Gitpul Resort MonoCMP -- Deploy to AWS EKS
echo ==============================================

:: ---- Prompt for AWS / EKS details -----------------------
echo.
set /p AWS_REGION="Enter AWS Region (e.g. us-east-1): "
if "!AWS_REGION!"=="" (
    echo ERROR: AWS Region is required.
    exit /b 1
)

set /p CLUSTER_NAME="Enter EKS Cluster Name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS Cluster Name is required.
    exit /b 1
)

set /p IMAGE_URI="Enter full Docker image URI: "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

:: ---- Prompt for application environment variables -------
echo.
echo --- Application Environment Variables ---
echo (Press Enter to keep default value)

set /p REDIS_HOST_VAL="Enter REDIS_HOST (ElastiCache endpoint) [localhost]: "
if "!REDIS_HOST_VAL!"=="" set "REDIS_HOST_VAL=localhost"

set /p REDIS_PORT_VAL="Enter REDIS_PORT [6379]: "
if "!REDIS_PORT_VAL!"=="" set "REDIS_PORT_VAL=6379"

set /p REDIS_SSL_VAL="Enter REDIS_SSL (true/false) [false]: "
if "!REDIS_SSL_VAL!"=="" set "REDIS_SSL_VAL=false"

set /p PAYMENT_API_URL_VAL="Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: "
if "!PAYMENT_API_URL_VAL!"=="" set "PAYMENT_API_URL_VAL=http://payment-service:9090/payments/charge"

set /p BOOKING_CACHE_TTL_VAL="Enter BOOKING_CACHE_TTL_SECONDS [3600]: "
if "!BOOKING_CACHE_TTL_VAL!"=="" set "BOOKING_CACHE_TTL_VAL=3600"

:: ---- Configure kubectl for EKS ---------------------------
echo.
echo Configuring kubectl for EKS cluster: !CLUSTER_NAME! in !AWS_REGION! ...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to configure kubectl for EKS.
    exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to EKS cluster.
    exit /b 1
)

:: ---- Update Kubernetes manifests with actual values ------
echo.
echo Updating Kubernetes manifests with deployment values...

powershell -Command "(Get-Content '!K8S_DIR!\deployment.yaml') -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content '!K8S_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!K8S_DIR!\deployment.yaml') -replace '{{REDIS_HOST}}', '!REDIS_HOST_VAL!' | Set-Content '!K8S_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!K8S_DIR!\deployment.yaml') -replace '{{REDIS_PORT}}', '!REDIS_PORT_VAL!' | Set-Content '!K8S_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!K8S_DIR!\deployment.yaml') -replace '{{REDIS_SSL}}', '!REDIS_SSL_VAL!' | Set-Content '!K8S_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!K8S_DIR!\deployment.yaml') -replace '{{PAYMENT_API_URL}}', '!PAYMENT_API_URL_VAL!' | Set-Content '!K8S_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!K8S_DIR!\deployment.yaml') -replace '{{BOOKING_CACHE_TTL_SECONDS}}', '!BOOKING_CACHE_TTL_VAL!' | Set-Content '!K8S_DIR!\deployment.yaml'"

echo Manifests updated.

:: ---- Apply Kubernetes manifests in order -----------------
echo.
echo Applying Kubernetes manifests...

echo   [1/4] Applying namespace...
kubectl apply -f "!K8S_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo   [2/4] Applying deployment...
kubectl apply -f "!K8S_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo   [3/4] Applying service...
kubectl apply -f "!K8S_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo   [4/4] Applying ingress...
kubectl apply -f "!K8S_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

:: ---- Wait for rollout ------------------------------------
echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed.
    echo Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

:: ---- Verify resources ------------------------------------
echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ==============================================
echo   Deployment Complete!
echo   Application: !APP_NAME!
echo   Namespace:   !NAMESPACE!
echo   Image:       !IMAGE_URI!
echo   Check URL:   kubectl get ingress -n !NAMESPACE!
echo ==============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

endlocal
exit /b 0
