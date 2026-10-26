@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: ============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "K8S_DIR=kubernetes"

echo ==============================================
echo   ResortsLite - AWS EKS Deployment Script
echo ==============================================
echo.

:: ── AWS / EKS configuration ────────────────────────────────
set /p "AWS_REGION=Enter AWS Region (e.g. us-east-1): "
if "!AWS_REGION!"=="" (
    echo ERROR: AWS Region is required.
    exit /b 1
)

set /p "CLUSTER_NAME=Enter EKS Cluster Name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS Cluster Name is required.
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
echo.

set /p "SPRING_DATASOURCE_URL=Enter SPRING_DATASOURCE_URL (Oracle JDBC URL): "
if "!SPRING_DATASOURCE_URL!"=="" set "SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL"

set /p "SPRING_DATASOURCE_USERNAME=Enter SPRING_DATASOURCE_USERNAME: "
if "!SPRING_DATASOURCE_USERNAME!"=="" set "SPRING_DATASOURCE_USERNAME=admin"

set /p "SPRING_DATASOURCE_PASSWORD=Enter SPRING_DATASOURCE_PASSWORD: "
if "!SPRING_DATASOURCE_PASSWORD!"=="" set "SPRING_DATASOURCE_PASSWORD=changeme"

set /p "REDIS_HOST=Enter REDIS_HOST (ElastiCache endpoint): "
if "!REDIS_HOST!"=="" set "REDIS_HOST=localhost"

set /p "REDIS_PORT=Enter REDIS_PORT [6379]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"

set /p "REDIS_PASSWORD=Enter REDIS_PASSWORD (leave blank if none): "

set /p "REDIS_SSL=Enter REDIS_SSL (true/false) [false]: "
if "!REDIS_SSL!"=="" set "REDIS_SSL=false"

set /p "PAYMENT_API_URL=Enter PAYMENT_API_URL: "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-service:9090/payments/charge"

set /p "REPORT_BASE_PATH=Enter REPORT_BASE_PATH [/var/reports]: "
if "!REPORT_BASE_PATH!"=="" set "REPORT_BASE_PATH=/var/reports"

:: ── Configure kubectl for EKS ──────────────────────────────
echo.
echo Configuring kubectl for EKS cluster '!CLUSTER_NAME!' in '!AWS_REGION!'...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to configure kubectl.
    exit /b 1
)
echo kubectl configured.

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to EKS cluster.
    exit /b 1
)

:: ── Substitute placeholders in manifests ──────────────────
echo.
echo Updating Kubernetes manifests with deployment values...

copy /Y "%K8S_DIR%\deployment.yaml" "%K8S_DIR%\deployment.yaml.deploy" >nul

:: Use PowerShell for sed-like substitution on Windows
powershell -Command "(Get-Content '%K8S_DIR%\deployment.yaml.deploy') -replace '\{\{IMAGE_URI\}\}', '!IMAGE_URI!' | Set-Content '%K8S_DIR%\deployment.yaml.deploy'"
powershell -Command "(Get-Content '%K8S_DIR%\deployment.yaml.deploy') -replace '\{\{SPRING_DATASOURCE_URL\}\}', '!SPRING_DATASOURCE_URL!' | Set-Content '%K8S_DIR%\deployment.yaml.deploy'"
powershell -Command "(Get-Content '%K8S_DIR%\deployment.yaml.deploy') -replace '\{\{SPRING_DATASOURCE_USERNAME\}\}', '!SPRING_DATASOURCE_USERNAME!' | Set-Content '%K8S_DIR%\deployment.yaml.deploy'"
powershell -Command "(Get-Content '%K8S_DIR%\deployment.yaml.deploy') -replace '\{\{SPRING_DATASOURCE_PASSWORD\}\}', '!SPRING_DATASOURCE_PASSWORD!' | Set-Content '%K8S_DIR%\deployment.yaml.deploy'"
powershell -Command "(Get-Content '%K8S_DIR%\deployment.yaml.deploy') -replace '\{\{REDIS_HOST\}\}', '!REDIS_HOST!' | Set-Content '%K8S_DIR%\deployment.yaml.deploy'"
powershell -Command "(Get-Content '%K8S_DIR%\deployment.yaml.deploy') -replace '\{\{REDIS_PORT\}\}', '!REDIS_PORT!' | Set-Content '%K8S_DIR%\deployment.yaml.deploy'"
powershell -Command "(Get-Content '%K8S_DIR%\deployment.yaml.deploy') -replace '\{\{REDIS_PASSWORD\}\}', '!REDIS_PASSWORD!' | Set-Content '%K8S_DIR%\deployment.yaml.deploy'"
powershell -Command "(Get-Content '%K8S_DIR%\deployment.yaml.deploy') -replace '\{\{REDIS_SSL\}\}', '!REDIS_SSL!' | Set-Content '%K8S_DIR%\deployment.yaml.deploy'"
powershell -Command "(Get-Content '%K8S_DIR%\deployment.yaml.deploy') -replace '\{\{PAYMENT_API_URL\}\}', '!PAYMENT_API_URL!' | Set-Content '%K8S_DIR%\deployment.yaml.deploy'"
powershell -Command "(Get-Content '%K8S_DIR%\deployment.yaml.deploy') -replace '\{\{REPORT_BASE_PATH\}\}', '!REPORT_BASE_PATH!' | Set-Content '%K8S_DIR%\deployment.yaml.deploy'"

echo Manifests updated.

:: ── Apply Kubernetes manifests ─────────────────────────────
echo.
echo Applying Kubernetes manifests...

echo   [1/4] Applying namespace...
kubectl apply -f "%K8S_DIR%\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo   [2/4] Applying deployment...
kubectl apply -f "%K8S_DIR%\deployment.yaml.deploy"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo   [3/4] Applying service...
kubectl apply -f "%K8S_DIR%\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo   [4/4] Applying ingress...
kubectl apply -f "%K8S_DIR%\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

:: ── Clean up temporary file ────────────────────────────────
del /f /q "%K8S_DIR%\deployment.yaml.deploy" >nul 2>&1

:: ── Wait for rollout ───────────────────────────────────────
echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed.
    echo Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

:: ── Verify resources ───────────────────────────────────────
echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ==============================================
echo   Deployment Complete!
echo   Namespace : !NAMESPACE!
echo   Image     : !IMAGE_URI!
echo.
echo   Rollback command:
echo     kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
echo ==============================================

endlocal
