@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: Usage: scripts\deploy-image.bat
:: Run from repository root directory
:: Prerequisites: aws-cli, kubectl
:: ============================================================

echo ==============================================
echo   ResortsLite - Deploy to AWS EKS
echo ==============================================
echo.

:: ---- AWS / EKS configuration ----
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

set /p IMAGE_URI="Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

echo.
echo ---- Application Environment Variables ----
echo Press Enter to use the default value shown in brackets.
echo.

set /p REDIS_HOST_VAL="Enter REDIS_HOST (ElastiCache primary endpoint) [localhost]: "
if "!REDIS_HOST_VAL!"=="" set REDIS_HOST_VAL=localhost

set /p REDIS_PORT_VAL="Enter REDIS_PORT [6379]: "
if "!REDIS_PORT_VAL!"=="" set REDIS_PORT_VAL=6379

set /p PAYMENT_API_URL_VAL="Enter PAYMENT_API_URL [http://payment-svc.payments.svc.cluster.local:9090/payments/charge]: "
if "!PAYMENT_API_URL_VAL!"=="" set PAYMENT_API_URL_VAL=http://payment-svc.payments.svc.cluster.local:9090/payments/charge

set /p REPORT_BASE_PATH_VAL="Enter REPORT_BASE_PATH [/var/reports]: "
if "!REPORT_BASE_PATH_VAL!"=="" set REPORT_BASE_PATH_VAL=/var/reports

set /p BACKUP_PATH_VAL="Enter BACKUP_PATH [/var/backups/resorts]: "
if "!BACKUP_PATH_VAL!"=="" set BACKUP_PATH_VAL=/var/backups/resorts

echo.
echo Configuring kubectl for EKS cluster: !CLUSTER_NAME! in !AWS_REGION! ...
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

echo.
echo Substituting placeholders in Kubernetes manifests...

:: Copy manifests to a temporary directory
xcopy /E /I /Y kubernetes kubernetes_deploy_tmp >nul

:: Use PowerShell to perform sed-like substitutions
powershell -Command "(Get-Content 'kubernetes_deploy_tmp\deployment.yaml') -replace '\{\{IMAGE_URI\}\}', '!IMAGE_URI!' | Set-Content 'kubernetes_deploy_tmp\deployment.yaml'"
powershell -Command "(Get-Content 'kubernetes_deploy_tmp\deployment.yaml') -replace '\{\{REDIS_HOST\}\}', '!REDIS_HOST_VAL!' | Set-Content 'kubernetes_deploy_tmp\deployment.yaml'"
powershell -Command "(Get-Content 'kubernetes_deploy_tmp\deployment.yaml') -replace '\{\{REDIS_PORT\}\}', '!REDIS_PORT_VAL!' | Set-Content 'kubernetes_deploy_tmp\deployment.yaml'"
powershell -Command "(Get-Content 'kubernetes_deploy_tmp\deployment.yaml') -replace '\{\{PAYMENT_API_URL\}\}', '!PAYMENT_API_URL_VAL!' | Set-Content 'kubernetes_deploy_tmp\deployment.yaml'"
powershell -Command "(Get-Content 'kubernetes_deploy_tmp\deployment.yaml') -replace '\{\{REPORT_BASE_PATH\}\}', '!REPORT_BASE_PATH_VAL!' | Set-Content 'kubernetes_deploy_tmp\deployment.yaml'"
powershell -Command "(Get-Content 'kubernetes_deploy_tmp\deployment.yaml') -replace '\{\{BACKUP_PATH\}\}', '!BACKUP_PATH_VAL!' | Set-Content 'kubernetes_deploy_tmp\deployment.yaml'"

echo.
echo Applying Kubernetes manifests...
kubectl apply -f kubernetes_deploy_tmp\namespace.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

kubectl apply -f kubernetes_deploy_tmp\deployment.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

kubectl apply -f kubernetes_deploy_tmp\service.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

kubectl apply -f kubernetes_deploy_tmp\ingress.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/resortslite -n resortslite --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed.
    echo Run: kubectl rollout undo deployment/resortslite -n resortslite
    exit /b 1
)

echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n resortslite

echo.
echo Fetching application URL from Ingress...
for /f "delims=" %%h in ('kubectl get ingress resortslite-ingress -n resortslite -o jsonpath^="{.status.loadBalancer.ingress[0].hostname}" 2^>nul') do set INGRESS_HOST=%%h
if "!INGRESS_HOST!"=="" set INGRESS_HOST=pending
echo Application URL: http://!INGRESS_HOST!

:: Clean up temporary manifests
rmdir /S /Q kubernetes_deploy_tmp

echo.
echo ==============================================
echo   Deployment Complete!
echo   Namespace : resortslite
echo   Image     : !IMAGE_URI!
echo   Cluster   : !CLUSTER_NAME! (!AWS_REGION!)
echo ==============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/resortslite -n resortslite

endlocal
