@echo off
setlocal enabledelayedexpansion

REM ============================================
REM Deploy to AWS EKS Script (Windows)
REM For ResortsLite Spring Boot Application
REM ============================================

echo ==========================================
echo AWS EKS Deployment Script
echo ==========================================
echo.

REM Prompt for AWS configuration
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

REM Prompt for environment-specific configuration
echo.
echo === Application Configuration ===
echo Enter values for environment variables (or press Enter to use defaults)
echo.

set /p SPRING_DATASOURCE_URL="Enter SPRING_DATASOURCE_URL (default: jdbc:h2:mem:resortdb): "
if "!SPRING_DATASOURCE_URL!"=="" set SPRING_DATASOURCE_URL=jdbc:h2:mem:resortdb

set /p SPRING_DATASOURCE_USERNAME="Enter SPRING_DATASOURCE_USERNAME (default: sa): "
if "!SPRING_DATASOURCE_USERNAME!"=="" set SPRING_DATASOURCE_USERNAME=sa

set /p SPRING_DATASOURCE_PASSWORD="Enter SPRING_DATASOURCE_PASSWORD (default: empty): "
if "!SPRING_DATASOURCE_PASSWORD!"=="" set SPRING_DATASOURCE_PASSWORD=

set /p REDIS_HOST="Enter REDIS_HOST (default: localhost): "
if "!REDIS_HOST!"=="" set REDIS_HOST=localhost

set /p REDIS_PORT="Enter REDIS_PORT (default: 6379): "
if "!REDIS_PORT!"=="" set REDIS_PORT=6379

set /p REDIS_PASSWORD="Enter REDIS_PASSWORD (default: empty): "
if "!REDIS_PASSWORD!"=="" set REDIS_PASSWORD=

set /p PAYMENT_API_URL="Enter PAYMENT_API_URL (default: http://payment-service:9090/payments/charge): "
if "!PAYMENT_API_URL!"=="" set PAYMENT_API_URL=http://payment-service:9090/payments/charge

set /p APP_PAYMENT_ENDPOINT="Enter APP_PAYMENT_ENDPOINT (default: http://payment-svc.internal:9090/charge): "
if "!APP_PAYMENT_ENDPOINT!"=="" set APP_PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge

set /p APP_INVENTORY_ENDPOINT="Enter APP_INVENTORY_ENDPOINT (default: http://inventory-svc.internal:8081/rooms): "
if "!APP_INVENTORY_ENDPOINT!"=="" set APP_INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms

set /p APP_NOTIFICATION_ENDPOINT="Enter APP_NOTIFICATION_ENDPOINT (default: http://notify.internal:7070/send): "
if "!APP_NOTIFICATION_ENDPOINT!"=="" set APP_NOTIFICATION_ENDPOINT=http://notify.internal:7070/send

echo.
echo ==========================================
echo Configuring kubectl for EKS
echo ==========================================
echo.

REM Configure kubectl to use EKS cluster
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
    echo ERROR: Cannot connect to Kubernetes cluster
    exit /b 1
)

echo.
echo ==========================================
echo Updating Kubernetes Manifests
echo ==========================================
echo.

REM Create temporary directory for modified manifests
set TEMP_DIR=%TEMP%\k8s-deploy-%RANDOM%
mkdir "!TEMP_DIR!"
xcopy /E /I /Q kubernetes "!TEMP_DIR!" >nul

REM Replace placeholders in deployment.yaml using PowerShell
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{SPRING_DATASOURCE_URL}}', '!SPRING_DATASOURCE_URL!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{SPRING_DATASOURCE_USERNAME}}', '!SPRING_DATASOURCE_USERNAME!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{SPRING_DATASOURCE_PASSWORD}}', '!SPRING_DATASOURCE_PASSWORD!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{REDIS_HOST}}', '!REDIS_HOST!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{REDIS_PORT}}', '!REDIS_PORT!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{REDIS_PASSWORD}}', '!REDIS_PASSWORD!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{PAYMENT_API_URL}}', '!PAYMENT_API_URL!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{APP_PAYMENT_ENDPOINT}}', '!APP_PAYMENT_ENDPOINT!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{APP_INVENTORY_ENDPOINT}}', '!APP_INVENTORY_ENDPOINT!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{APP_NOTIFICATION_ENDPOINT}}', '!APP_NOTIFICATION_ENDPOINT!' | Set-Content '!TEMP_DIR!\deployment.yaml'"

echo Manifests updated successfully

echo.
echo ==========================================
echo Deploying to Kubernetes
echo ==========================================
echo.

REM Apply Kubernetes manifests in order
echo Creating namespace...
kubectl apply -f "!TEMP_DIR!\namespace.yaml"

echo.
echo Deploying application...
kubectl apply -f "!TEMP_DIR!\deployment.yaml"

echo.
echo Creating service...
kubectl apply -f "!TEMP_DIR!\service.yaml"

echo.
echo Creating ingress...
kubectl apply -f "!TEMP_DIR!\ingress.yaml"

echo.
echo ==========================================
echo Waiting for Deployment Rollout
echo ==========================================
echo.

REM Wait for deployment to complete
kubectl rollout status deployment/resortslite -n resortslite --timeout=5m

if !ERRORLEVEL! neq 0 (
    echo WARNING: Deployment rollout did not complete successfully
    echo Check pod status with: kubectl get pods -n resortslite
)

echo.
echo ==========================================
echo Deployment Status
echo ==========================================
echo.

REM Display deployment status
kubectl get pods,svc,ingress -n resortslite

echo.
echo ==========================================
echo Deployment Completed Successfully!
echo ==========================================
echo.
echo Application deployed to namespace: resortslite
echo.
echo To check application logs:
echo   kubectl logs -f deployment/resortslite -n resortslite
echo.
echo To check pod status:
echo   kubectl get pods -n resortslite
echo.
echo To access the application:
echo   kubectl port-forward svc/resortslite-service 8080:80 -n resortslite
echo   Then open: http://localhost:8080
echo.
echo To get ingress URL:
echo   kubectl get ingress resortslite-ingress -n resortslite
echo.

REM Cleanup temporary directory
rmdir /S /Q "!TEMP_DIR!"

endlocal
