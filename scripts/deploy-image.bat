@echo off
setlocal enabledelayedexpansion

REM =============================================================================
REM deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
REM Usage: scripts\deploy-image.bat
REM Run from the repository root directory.
REM =============================================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"

echo ==============================================
echo   ResortsLite -- AWS EKS Deployment
echo ==============================================
echo.

REM ── Collect deployment inputs ─────────────────────────────────────────────────
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

set /p "IMAGE_URI=Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

echo.
echo --- Application Environment Variables ---
echo Press Enter to skip any variable.
echo.

set /p "SPRING_DATASOURCE_URL=Enter SPRING_DATASOURCE_URL (e.g. jdbc:oracle:thin:@host:1521:sid): "
set /p "REDIS_HOST=Enter REDIS_HOST (Amazon ElastiCache endpoint): "
set /p "REDIS_PORT=Enter REDIS_PORT (default 6379): "
set /p "REDIS_SSL=Enter REDIS_SSL (true/false, default false): "
set /p "PAYMENT_API_URL=Enter PAYMENT_API_URL (e.g. https://payment-service/payments/charge): "

if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"
if "!REDIS_SSL!"=="" set "REDIS_SSL=false"

echo.
echo ==============================================
echo   Configuring kubectl for EKS cluster...
echo ==============================================
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to configure kubectl.
    exit /b 1
)

echo.
echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to EKS cluster.
    exit /b 1
)

REM ── Copy manifests to temp directory ─────────────────────────────────────────
echo.
echo Updating Kubernetes manifests...
if exist kubernetes_deploy_tmp rmdir /s /q kubernetes_deploy_tmp
xcopy /e /i /q kubernetes kubernetes_deploy_tmp >nul

REM ── Replace placeholders using PowerShell ────────────────────────────────────
powershell -NoProfile -Command ^
  "$content = Get-Content 'kubernetes_deploy_tmp\deployment.yaml' -Raw; ^
   $content = $content -replace '\{\{IMAGE_URI\}\}', '!IMAGE_URI!'; ^
   if ('!SPRING_DATASOURCE_URL!' -ne '') { $content = $content -replace '\{\{SPRING_DATASOURCE_URL\}\}', '!SPRING_DATASOURCE_URL!' }; ^
   if ('!REDIS_HOST!' -ne '') { $content = $content -replace '\{\{REDIS_HOST\}\}', '!REDIS_HOST!' }; ^
   $content = $content -replace '\{\{REDIS_PORT\}\}', '!REDIS_PORT!'; ^
   $content = $content -replace '\{\{REDIS_SSL\}\}', '!REDIS_SSL!'; ^
   if ('!PAYMENT_API_URL!' -ne '') { $content = $content -replace '\{\{PAYMENT_API_URL\}\}', '!PAYMENT_API_URL!' }; ^
   Set-Content 'kubernetes_deploy_tmp\deployment.yaml' $content"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to update deployment manifest.
    exit /b 1
)

REM ── Apply manifests in order ──────────────────────────────────────────────────
echo.
echo Applying Kubernetes manifests...

echo   [1/4] Applying namespace...
kubectl apply -f kubernetes_deploy_tmp\namespace.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo   [2/4] Applying deployment...
kubectl apply -f kubernetes_deploy_tmp\deployment.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo   [3/4] Applying service...
kubectl apply -f kubernetes_deploy_tmp\service.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo   [4/4] Applying ingress...
kubectl apply -f kubernetes_deploy_tmp\ingress.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

REM ── Wait for rollout ──────────────────────────────────────────────────────────
echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo.
    echo ERROR: Deployment rollout timed out or failed.
    echo Run the following to investigate:
    echo   kubectl describe deployment/!APP_NAME! -n !NAMESPACE!
    echo   kubectl get pods -n !NAMESPACE!
    echo   kubectl logs -l app=!APP_NAME! -n !NAMESPACE! --tail=50
    echo.
    echo To rollback:
    echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    rmdir /s /q kubernetes_deploy_tmp
    exit /b 1
)

REM ── Verify resources ──────────────────────────────────────────────────────────
echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

REM ── Cleanup temp directory ────────────────────────────────────────────────────
rmdir /s /q kubernetes_deploy_tmp

echo.
echo ==============================================
echo   Deployment complete!
echo   Check ingress for the application URL:
echo   kubectl get ingress -n !NAMESPACE!
echo ==============================================

endlocal
