@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: ============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "MANIFEST_DIR=kubernetes"
set "TEMP_DIR=%TEMP%\resortslite-k8s-deploy"

echo ==============================================
echo   ResortsLite — Deploy to AWS EKS
echo ==============================================

:: ---- AWS / EKS configuration ----
set /p "AWS_REGION=Enter AWS region [us-east-1]: "
if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

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

:: ---- Application environment variables ----
echo.
echo --- Application Configuration (press Enter to use defaults) ---

set /p "SPRING_DATASOURCE_URL=Enter SPRING_DATASOURCE_URL [jdbc:oracle:thin:@db-host:1521:ORCL]: "
if "!SPRING_DATASOURCE_URL!"=="" set "SPRING_DATASOURCE_URL=jdbc:oracle:thin:@db-host:1521:ORCL"

set /p "SPRING_DATASOURCE_USERNAME=Enter SPRING_DATASOURCE_USERNAME [admin]: "
if "!SPRING_DATASOURCE_USERNAME!"=="" set "SPRING_DATASOURCE_USERNAME=admin"

set /p "SPRING_DATASOURCE_PASSWORD=Enter SPRING_DATASOURCE_PASSWORD: "
if "!SPRING_DATASOURCE_PASSWORD!"=="" set "SPRING_DATASOURCE_PASSWORD=changeme"

set /p "REDIS_HOST=Enter REDIS_HOST [localhost]: "
if "!REDIS_HOST!"=="" set "REDIS_HOST=localhost"

set /p "REDIS_PORT=Enter REDIS_PORT [6379]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"

set /p "REDIS_PASSWORD=Enter REDIS_PASSWORD (leave blank if none): "

set /p "PAYMENT_API_URL=Enter PAYMENT_API_URL [http://payment-svc:9090/payments/charge]: "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-svc:9090/payments/charge"

:: ---- Configure kubectl ----
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

:: ---- Copy manifests to temp directory ----
echo.
echo Preparing Kubernetes manifests...
if exist "!TEMP_DIR!" rmdir /s /q "!TEMP_DIR!"
xcopy /e /i /q "!MANIFEST_DIR!" "!TEMP_DIR!" >nul
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to copy manifest files.
    exit /b 1
)

:: ---- Substitute placeholders using PowerShell ----
echo Updating manifests with deployment values...
powershell -NoProfile -Command ^
  "$f = '!TEMP_DIR!\deployment.yaml';" ^
  "$c = Get-Content $f -Raw;" ^
  "$c = $c -replace '{{IMAGE_URI}}','!IMAGE_URI!';" ^
  "$c = $c -replace '{{SPRING_DATASOURCE_URL}}','!SPRING_DATASOURCE_URL!';" ^
  "$c = $c -replace '{{SPRING_DATASOURCE_USERNAME}}','!SPRING_DATASOURCE_USERNAME!';" ^
  "$c = $c -replace '{{SPRING_DATASOURCE_PASSWORD}}','!SPRING_DATASOURCE_PASSWORD!';" ^
  "$c = $c -replace '{{REDIS_HOST}}','!REDIS_HOST!';" ^
  "$c = $c -replace '{{REDIS_PORT}}','!REDIS_PORT!';" ^
  "$c = $c -replace '{{REDIS_PASSWORD}}','!REDIS_PASSWORD!';" ^
  "$c = $c -replace '{{PAYMENT_API_URL}}','!PAYMENT_API_URL!';" ^
  "Set-Content $f $c -NoNewline"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to update deployment manifest.
    exit /b 1
)

:: ---- Apply manifests ----
echo.
echo Applying Kubernetes manifests...

echo   [1/4] Applying namespace...
kubectl apply -f "!TEMP_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo   [2/4] Applying deployment...
kubectl apply -f "!TEMP_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo   [3/4] Applying service...
kubectl apply -f "!TEMP_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo   [4/4] Applying ingress...
kubectl apply -f "!TEMP_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

:: ---- Wait for rollout ----
echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed. Running rollback...
    kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

:: ---- Verify resources ----
echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

:: ---- Display access URL ----
echo.
for /f "delims=" %%H in ('kubectl get ingress !APP_NAME!-ingress -n !NAMESPACE! -o jsonpath^="{.status.loadBalancer.ingress[0].hostname}" 2^>nul') do set "INGRESS_HOST=%%H"
if "!INGRESS_HOST!"=="" set "INGRESS_HOST=^<pending^>"

echo ==============================================
echo   Deployment Complete!
echo   Application URL: http://!INGRESS_HOST!
echo   Namespace:       !NAMESPACE!
echo   Image:           !IMAGE_URI!
echo ==============================================
echo.
echo Useful commands:
echo   kubectl get pods -n !NAMESPACE!
echo   kubectl logs -l app=!APP_NAME! -n !NAMESPACE! --tail=100
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

:: Cleanup
rmdir /s /q "!TEMP_DIR!" >nul 2>&1

endlocal
