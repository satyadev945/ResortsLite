@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy ResortsLite to GCP GKE (Windows)
:: ============================================================

echo ==============================================
echo   ResortsLite - GKE Deployment
echo ==============================================
echo.

:: ---- GCP / GKE configuration ----
set /p GCP_PROJECT="Enter GCP Project ID: "
if "!GCP_PROJECT!"=="" (
    echo GCP Project ID is required. Exiting.
    exit /b 1
)

set /p GCP_ZONE="Enter GCP Zone (e.g. us-central1-a): "
if "!GCP_ZONE!"=="" (
    echo GCP Zone is required. Exiting.
    exit /b 1
)

set /p CLUSTER_NAME="Enter GKE Cluster Name: "
if "!CLUSTER_NAME!"=="" (
    echo GKE Cluster Name is required. Exiting.
    exit /b 1
)

set /p IMAGE_URI="Enter full Docker image URI (e.g. us-central1-docker.pkg.dev/my-project/my-repo/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo Docker image URI is required. Exiting.
    exit /b 1
)

echo.
echo ---- Application Environment Variables ----
echo Press Enter to keep the default value.
echo.

set /p REDIS_HOST="Enter REDIS_HOST (default: redis-service): "
if "!REDIS_HOST!"=="" set "REDIS_HOST=redis-service"

set /p REDIS_PORT="Enter REDIS_PORT (default: 6379): "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"

set /p PUBSUB_GCP_PROJECT="Enter GCP_PROJECT_ID for Pub/Sub (default: !GCP_PROJECT!): "
if "!PUBSUB_GCP_PROJECT!"=="" set "PUBSUB_GCP_PROJECT=!GCP_PROJECT!"

set /p PUBSUB_BOOKING_TOPIC="Enter PUBSUB_BOOKING_TOPIC (default: booking-events): "
if "!PUBSUB_BOOKING_TOPIC!"=="" set "PUBSUB_BOOKING_TOPIC=booking-events"

set /p PUBSUB_REPORT_TOPIC="Enter PUBSUB_REPORT_TOPIC (default: report-events): "
if "!PUBSUB_REPORT_TOPIC!"=="" set "PUBSUB_REPORT_TOPIC=report-events"

set /p REPORT_BASE_PATH="Enter REPORT_BASE_PATH (default: /var/reports): "
if "!REPORT_BASE_PATH!"=="" set "REPORT_BASE_PATH=/var/reports"

set /p BOOKING_CACHE_TTL="Enter BOOKING_CACHE_TTL_SECONDS (default: 3600): "
if "!BOOKING_CACHE_TTL!"=="" set "BOOKING_CACHE_TTL=3600"

set /p PAYMENT_API_URL="Enter PAYMENT_API_URL (default: http://payment-service:9090/payments/charge): "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-service:9090/payments/charge"

echo.
echo ==============================================
echo   Configuring kubectl for GKE cluster
echo ==============================================
gcloud container clusters get-credentials !CLUSTER_NAME! --zone !GCP_ZONE! --project !GCP_PROJECT!
if !ERRORLEVEL! neq 0 (
    echo Failed to configure kubectl. Exiting.
    exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo Cannot connect to cluster. Exiting.
    exit /b 1
)

echo.
echo ==============================================
echo   Updating Kubernetes manifests
echo ==============================================

:: Use PowerShell to perform sed-like replacements on Windows
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '\{\{IMAGE_URI\}\}', '!IMAGE_URI!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '\{\{REDIS_HOST\}\}', '!REDIS_HOST!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '\{\{REDIS_PORT\}\}', '!REDIS_PORT!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '\{\{GCP_PROJECT_ID\}\}', '!PUBSUB_GCP_PROJECT!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '\{\{PUBSUB_BOOKING_TOPIC\}\}', '!PUBSUB_BOOKING_TOPIC!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '\{\{PUBSUB_REPORT_TOPIC\}\}', '!PUBSUB_REPORT_TOPIC!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '\{\{REPORT_BASE_PATH\}\}', '!REPORT_BASE_PATH!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '\{\{BOOKING_CACHE_TTL_SECONDS\}\}', '!BOOKING_CACHE_TTL!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '\{\{PAYMENT_API_URL\}\}', '!PAYMENT_API_URL!' | Set-Content kubernetes\deployment.yaml"

echo.
echo ==============================================
echo   Applying Kubernetes manifests
echo ==============================================

echo 1/4 Applying namespace...
kubectl apply -f kubernetes\namespace.yaml
if !ERRORLEVEL! neq 0 ( echo Failed to apply namespace. & exit /b 1 )

echo 2/4 Applying deployment...
kubectl apply -f kubernetes\deployment.yaml
if !ERRORLEVEL! neq 0 ( echo Failed to apply deployment. & exit /b 1 )

echo 3/4 Applying service...
kubectl apply -f kubernetes\service.yaml
if !ERRORLEVEL! neq 0 ( echo Failed to apply service. & exit /b 1 )

echo 4/4 Applying ingress...
kubectl apply -f kubernetes\ingress.yaml
if !ERRORLEVEL! neq 0 ( echo Failed to apply ingress. & exit /b 1 )

echo.
echo ==============================================
echo   Waiting for rollout to complete
echo ==============================================
kubectl rollout status deployment/resortslite -n resortslite --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo Rollout did not complete successfully.
    echo To rollback run: kubectl rollout undo deployment/resortslite -n resortslite
    exit /b 1
)

echo.
echo ==============================================
echo   Deployment verification
echo ==============================================
kubectl get pods,svc,ingress -n resortslite

echo.
echo Deployment complete!
echo.
echo Rollback command if needed:
echo   kubectl rollout undo deployment/resortslite -n resortslite

endlocal
