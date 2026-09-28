@echo off
setlocal enabledelayedexpansion

REM ============================================================================
REM Deploy ResortsLite to Google Kubernetes Engine (GKE) - Windows
REM ============================================================================

echo ============================================
echo ResortsLite - GKE Deployment Script
echo ============================================
echo.

REM Prompt for GCP configuration
echo === GCP Configuration ===
set /p GCP_PROJECT="Enter GCP Project ID: "
set /p GCP_ZONE="Enter GCP Zone (e.g., us-central1-a): "
set /p CLUSTER_NAME="Enter GKE Cluster Name: "

if "!GCP_PROJECT!"=="" (
    echo ERROR: GCP Project ID is required!
    exit /b 1
)
if "!GCP_ZONE!"=="" (
    echo ERROR: GCP Zone is required!
    exit /b 1
)
if "!CLUSTER_NAME!"=="" (
    echo ERROR: GKE Cluster Name is required!
    exit /b 1
)

echo.
echo === Docker Image Configuration ===
set /p IMAGE_URI="Enter Docker Image URI (e.g., us-central1-docker.pkg.dev/project/repo/resortslite:latest): "

if "!IMAGE_URI!"=="" (
    echo ERROR: Docker Image URI is required!
    exit /b 1
)

echo.
echo === Application Configuration ===
echo Configure environment variables for external dependencies
echo (Press Enter to skip optional values)
echo.

REM Database Configuration
set /p SPRING_DATASOURCE_URL="Enter Oracle Database URL (e.g., jdbc:oracle:thin:@host:1521:ORCL): "
if "!SPRING_DATASOURCE_URL!"=="" set SPRING_DATASOURCE_URL=jdbc:oracle:thin:@oracle-db:1521:ORCL

set /p SPRING_DATASOURCE_USERNAME="Enter Database Username: "
if "!SPRING_DATASOURCE_USERNAME!"=="" set SPRING_DATASOURCE_USERNAME=admin

set /p SPRING_DATASOURCE_PASSWORD="Enter Database Password: "
if "!SPRING_DATASOURCE_PASSWORD!"=="" set SPRING_DATASOURCE_PASSWORD=changeme

REM Redis Configuration
set /p REDIS_HOST="Enter Redis Host (Google Cloud Memorystore): "
if "!REDIS_HOST!"=="" set REDIS_HOST=redis-server

set /p REDIS_PORT="Enter Redis Port: "
if "!REDIS_PORT!"=="" set REDIS_PORT=6379

set /p REDIS_PASSWORD="Enter Redis Password (if any): "

REM External Service Endpoints
set /p PAYMENT_API_URL="Enter Payment API URL: "
if "!PAYMENT_API_URL!"=="" set PAYMENT_API_URL=http://payment-service:9090/payments/charge

set /p APP_INVENTORY_ENDPOINT="Enter Inventory Service Endpoint: "
if "!APP_INVENTORY_ENDPOINT!"=="" set APP_INVENTORY_ENDPOINT=http://inventory-service:8081/rooms

set /p APP_NOTIFICATION_ENDPOINT="Enter Notification Service Endpoint: "
if "!APP_NOTIFICATION_ENDPOINT!"=="" set APP_NOTIFICATION_ENDPOINT=http://notification-service:7070/send

echo.
echo ============================================
echo Configuring kubectl for GKE cluster...
echo ============================================

call gcloud container clusters get-credentials "!CLUSTER_NAME!" --zone "!GCP_ZONE!" --project "!GCP_PROJECT!"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to configure kubectl for GKE cluster!
    exit /b 1
)

echo.
echo Verifying cluster connectivity...
call kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to connect to cluster!
    exit /b 1
)

echo.
echo ============================================
echo Updating Kubernetes manifests...
echo ============================================

REM Create temporary directory for processed manifests
set TEMP_DIR=%TEMP%\k8s-deploy-%RANDOM%
mkdir "!TEMP_DIR!"
xcopy /E /I /Q kubernetes "!TEMP_DIR!"

REM Replace placeholders using PowerShell for better string handling
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{SPRING_DATASOURCE_URL}}', '!SPRING_DATASOURCE_URL!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{SPRING_DATASOURCE_USERNAME}}', '!SPRING_DATASOURCE_USERNAME!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{SPRING_DATASOURCE_PASSWORD}}', '!SPRING_DATASOURCE_PASSWORD!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{REDIS_HOST}}', '!REDIS_HOST!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{REDIS_PORT}}', '!REDIS_PORT!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{REDIS_PASSWORD}}', '!REDIS_PASSWORD!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{PAYMENT_API_URL}}', '!PAYMENT_API_URL!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{APP_INVENTORY_ENDPOINT}}', '!APP_INVENTORY_ENDPOINT!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{APP_NOTIFICATION_ENDPOINT}}', '!APP_NOTIFICATION_ENDPOINT!' | Set-Content '!TEMP_DIR!\deployment.yaml'"

echo Manifests updated successfully.

echo.
echo ============================================
echo Deploying to GKE...
echo ============================================

REM Apply namespace
echo Creating namespace...
call kubectl apply -f "!TEMP_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to create namespace!
    exit /b 1
)

REM Apply deployment
echo Deploying application...
call kubectl apply -f "!TEMP_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to deploy application!
    exit /b 1
)

REM Apply service
echo Creating service...
call kubectl apply -f "!TEMP_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to create service!
    exit /b 1
)

REM Apply ingress
echo Creating ingress...
call kubectl apply -f "!TEMP_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to create ingress!
    exit /b 1
)

echo.
echo ============================================
echo Waiting for deployment rollout...
echo ============================================

call kubectl rollout status deployment/resortslite -n resortslite --timeout=5m
if !ERRORLEVEL! neq 0 (
    echo WARNING: Deployment rollout did not complete successfully!
    echo Check pod status with: kubectl get pods -n resortslite
)

echo.
echo ============================================
echo Verifying deployment...
echo ============================================

call kubectl get pods,svc,ingress -n resortslite

echo.
echo ============================================
echo Deployment Summary
echo ============================================
echo Namespace: resortslite
echo Deployment: resortslite
echo Service: resortslite-service
echo Ingress: resortslite-ingress
echo.
echo To view logs:
echo   kubectl logs -f deployment/resortslite -n resortslite
echo.
echo To get pod status:
echo   kubectl get pods -n resortslite
echo.
echo To access the application:
echo   kubectl port-forward -n resortslite svc/resortslite-service 8080:80
echo   Then visit: http://localhost:8080
echo.
echo To get ingress IP (may take a few minutes):
echo   kubectl get ingress resortslite-ingress -n resortslite
echo ============================================

REM Cleanup temporary directory
rmdir /S /Q "!TEMP_DIR!"

echo.
echo Deployment completed successfully!

endlocal
