@echo off
setlocal enabledelayedexpansion

REM Deploy ResortsLite to AWS EKS
REM This script configures kubectl and deploys the application to EKS

echo ==========================================
echo AWS EKS Deployment Script - ResortsLite
echo ==========================================
echo.

REM Prompt for AWS EKS configuration
set /p AWS_REGION="Enter AWS Region (e.g., us-east-1): "
set /p CLUSTER_NAME="Enter EKS Cluster Name: "

if "!AWS_REGION!"=="" (
    echo ERROR: AWS Region is required
    exit /b 1
)

if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS Cluster Name is required
    exit /b 1
)

REM Prompt for Docker image URI
echo.
set /p IMAGE_URI="Enter Docker Image URI (e.g., 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "

if "!IMAGE_URI!"=="" (
    echo ERROR: Docker Image URI is required
    exit /b 1
)

REM Prompt for environment variables
echo.
echo === Application Configuration ===
echo Enter values for environment variables (or press Enter to skip):
echo.

set /p DATABASE_URL="DATABASE_URL (Oracle JDBC URL): "
set /p DATABASE_USERNAME="DATABASE_USERNAME: "
set /p DATABASE_PASSWORD="DATABASE_PASSWORD: "

set /p REDIS_HOST="REDIS_HOST (ElastiCache endpoint): "
set /p REDIS_PORT="REDIS_PORT (default: 6379): "
if "!REDIS_PORT!"=="" set REDIS_PORT=6379
set /p REDIS_PASSWORD="REDIS_PASSWORD: "

set /p PAYMENT_API_URL="PAYMENT_API_URL (default: http://payment-service:9090/payments/charge): "
if "!PAYMENT_API_URL!"=="" set PAYMENT_API_URL=http://payment-service:9090/payments/charge

REM Configure kubectl for EKS
echo.
echo ==========================================
echo Configuring kubectl for EKS
echo ==========================================
echo.

aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!

if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to configure kubectl for EKS cluster
    exit /b 1
)

REM Verify cluster connectivity
echo.
echo Verifying cluster connectivity...
kubectl cluster-info

if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to EKS cluster
    exit /b 1
)

REM Update Kubernetes manifests with actual values
echo.
echo ==========================================
echo Updating Kubernetes Manifests
echo ==========================================
echo.

REM Create temporary directory for processed manifests
set TEMP_DIR=%TEMP%\k8s-deploy-%RANDOM%
mkdir !TEMP_DIR!
xcopy /E /I /Q kubernetes !TEMP_DIR! >nul

REM Replace placeholders in deployment.yaml using PowerShell
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content '!TEMP_DIR!\deployment.yaml'"

if "!DATABASE_URL!"=="" (
    set DATABASE_URL=jdbc:oracle:thin:@localhost:1521:ORCL
)
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{DATABASE_URL}}', '!DATABASE_URL!' | Set-Content '!TEMP_DIR!\deployment.yaml'"

if "!DATABASE_USERNAME!"=="" (
    set DATABASE_USERNAME=admin
)
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{DATABASE_USERNAME}}', '!DATABASE_USERNAME!' | Set-Content '!TEMP_DIR!\deployment.yaml'"

if "!DATABASE_PASSWORD!"=="" (
    set DATABASE_PASSWORD=changeme
)
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{DATABASE_PASSWORD}}', '!DATABASE_PASSWORD!' | Set-Content '!TEMP_DIR!\deployment.yaml'"

if "!REDIS_HOST!"=="" (
    set REDIS_HOST=localhost
)
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{REDIS_HOST}}', '!REDIS_HOST!' | Set-Content '!TEMP_DIR!\deployment.yaml'"

powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{REDIS_PORT}}', '!REDIS_PORT!' | Set-Content '!TEMP_DIR!\deployment.yaml'"

if "!REDIS_PASSWORD!"=="" (
    set REDIS_PASSWORD=
)
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{REDIS_PASSWORD}}', '!REDIS_PASSWORD!' | Set-Content '!TEMP_DIR!\deployment.yaml'"

powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{PAYMENT_API_URL}}', '!PAYMENT_API_URL!' | Set-Content '!TEMP_DIR!\deployment.yaml'"

echo Manifests updated successfully

REM Apply Kubernetes manifests
echo.
echo ==========================================
echo Deploying to EKS
echo ==========================================
echo.

echo Creating namespace...
kubectl apply -f !TEMP_DIR!\namespace.yaml

echo.
echo Deploying application...
kubectl apply -f !TEMP_DIR!\deployment.yaml

echo.
echo Creating service...
kubectl apply -f !TEMP_DIR!\service.yaml

echo.
echo Creating ingress...
kubectl apply -f !TEMP_DIR!\ingress.yaml

REM Wait for deployment rollout
echo.
echo ==========================================
echo Waiting for Deployment Rollout
echo ==========================================
echo.

kubectl rollout status deployment/resortslite -n resortslite --timeout=5m

if !ERRORLEVEL! neq 0 (
    echo WARNING: Deployment rollout did not complete successfully
    echo Check pod status with: kubectl get pods -n resortslite
)

REM Verify deployment
echo.
echo ==========================================
echo Deployment Status
echo ==========================================
echo.

kubectl get pods,svc,ingress -n resortslite

REM Get ingress URL
echo.
echo ==========================================
echo Application Access Information
echo ==========================================
echo.

for /f "delims=" %%i in ('kubectl get ingress resortslite-ingress -n resortslite -o jsonpath^="{.status.loadBalancer.ingress[0].hostname}" 2^>nul') do set INGRESS_ADDRESS=%%i

if not "!INGRESS_ADDRESS!"=="" (
    echo Application URL: http://!INGRESS_ADDRESS!
    echo Health Check: http://!INGRESS_ADDRESS!/actuator/health
) else (
    echo Ingress is being provisioned. Check status with:
    echo kubectl get ingress -n resortslite
)

REM Cleanup temporary directory
rmdir /S /Q !TEMP_DIR!

echo.
echo ==========================================
echo Deployment Completed Successfully!
echo ==========================================
echo.
echo Useful commands:
echo   View pods:        kubectl get pods -n resortslite
echo   View logs:        kubectl logs -f deployment/resortslite -n resortslite
echo   View services:    kubectl get svc -n resortslite
echo   View ingress:     kubectl get ingress -n resortslite
echo   Describe pod:     kubectl describe pod ^<pod-name^> -n resortslite
echo.
echo To rollback deployment:
echo   kubectl rollout undo deployment/resortslite -n resortslite
echo.

endlocal
