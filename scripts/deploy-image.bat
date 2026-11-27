@echo off
setlocal enabledelayedexpansion

rem Deploys the pushed Resortlite_Mono1 image to AWS EKS with kubectl.
rem Run from the repository root with scripts\deploy-image.bat

set "NAME=resortlite-mono1"
set "NAMESPACE=resortlite-mono1"

where aws >nul 2>&1
if !ERRORLEVEL! neq 0 (
    echo The AWS CLI is required
    exit /b 1
)
where kubectl >nul 2>&1
if !ERRORLEVEL! neq 0 (
    echo kubectl is required
    exit /b 1
)

echo === Deploy !NAME! to AWS EKS ===
set /p AWS_REGION="Enter the AWS region (for example us-east-1): "
set /p CLUSTER_NAME="Enter the EKS cluster name: "
set /p IMAGE_URI="Enter the image URI (registry/repository and tag): "
if "!AWS_REGION!"=="" (
    echo Region is required
    exit /b 1
)
if "!CLUSTER_NAME!"=="" (
    echo Cluster name is required
    exit /b 1
)
if "!IMAGE_URI!"=="" (
    echo Image URI is required
    exit /b 1
)

set /p APP_PORT="Enter value for APP_PORT (or press Enter to skip, default 8080): "
if "!APP_PORT!"=="" set "APP_PORT=8080"
set /p SPRING_PROFILES_ACTIVE="Enter value for SPRING_PROFILES_ACTIVE (or press Enter to skip, default prod): "
if "!SPRING_PROFILES_ACTIVE!"=="" set "SPRING_PROFILES_ACTIVE=prod"
set /p REDIS_HOST="Enter value for REDIS_HOST (or press Enter to skip, default localhost): "
if "!REDIS_HOST!"=="" set "REDIS_HOST=localhost"
set /p REDIS_PORT="Enter value for REDIS_PORT (or press Enter to skip, default 6379): "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"
set /p REDIS_SSL="Enter value for REDIS_SSL (or press Enter to skip, default false): "
if "!REDIS_SSL!"=="" set "REDIS_SSL=false"
set /p PAYMENT_API_URL="Enter value for PAYMENT_API_URL (or press Enter to skip): "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-svc.internal:9090/payments/charge"
set /p REPORT_PATH="Enter value for REPORT_PATH (or press Enter to skip, default /app/reports): "
if "!REPORT_PATH!"=="" set "REPORT_PATH=/app/reports"
set /p BACKUP_PATH="Enter value for BACKUP_PATH (or press Enter to skip, default /app/backups): "
if "!BACKUP_PATH!"=="" set "BACKUP_PATH=/app/backups"
set /p CACHE_TTL="Enter value for CACHE_TTL (or press Enter to skip, default 1h): "
if "!CACHE_TTL!"=="" set "CACHE_TTL=1h"
set /p REDIS_PASSWORD="Enter value for REDIS_PASSWORD (ElastiCache auth token, input is visible): "

echo Configuring kubectl for cluster !CLUSTER_NAME! ...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
    echo aws eks update-kubeconfig failed
    exit /b 1
)
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo kubectl cannot reach the cluster
    exit /b 1
)

rem Work on a copy so that the manifests keep their placeholders
set "WORK_DIR=%TEMP%\resortlite-mono1-deploy"
if exist "!WORK_DIR!" rmdir /s /q "!WORK_DIR!"
mkdir "!WORK_DIR!"
xcopy /e /i /q /y kubernetes "!WORK_DIR!\kubernetes" >nul
if !ERRORLEVEL! neq 0 (
    echo Could not copy the manifests
    exit /b 1
)
set "DEPLOY_FILE=!WORK_DIR!\kubernetes\deployment.yaml"

powershell -NoProfile -Command "$f=$env:DEPLOY_FILE; $c=Get-Content -Raw $f; $c=$c.Replace('{{IMAGE_URI}}',$env:IMAGE_URI).Replace('{{NAMESPACE}}',$env:NAMESPACE).Replace('{{APP_PORT}}',$env:APP_PORT).Replace('{{SPRING_PROFILES_ACTIVE}}',$env:SPRING_PROFILES_ACTIVE).Replace('{{REDIS_HOST}}',$env:REDIS_HOST).Replace('{{REDIS_PORT}}',$env:REDIS_PORT).Replace('{{REDIS_SSL}}',$env:REDIS_SSL).Replace('{{PAYMENT_API_URL}}',$env:PAYMENT_API_URL).Replace('{{REPORT_PATH}}',$env:REPORT_PATH).Replace('{{BACKUP_PATH}}',$env:BACKUP_PATH).Replace('{{CACHE_TTL}}',$env:CACHE_TTL); Set-Content -NoNewline -Path $f -Value $c"
if !ERRORLEVEL! neq 0 (
    echo Placeholder replacement failed
    exit /b 1
)

echo Applying the namespace ...
kubectl apply -f "!WORK_DIR!\kubernetes\namespace.yaml"
if !ERRORLEVEL! neq 0 (
    echo Namespace apply failed
    exit /b 1
)

echo Creating the Secret !NAME!-secrets ...
kubectl create secret generic !NAME!-secrets -n !NAMESPACE! --from-literal=REDIS_PASSWORD="!REDIS_PASSWORD!" --dry-run=client -o yaml > "!WORK_DIR!\secret.yaml"
if !ERRORLEVEL! neq 0 (
    echo Secret creation failed
    exit /b 1
)
kubectl apply -f "!WORK_DIR!\secret.yaml"
if !ERRORLEVEL! neq 0 (
    echo Secret apply failed
    exit /b 1
)
del "!WORK_DIR!\secret.yaml" >nul 2>&1

echo Applying the deployment, service and ingress ...
kubectl apply -f "!DEPLOY_FILE!"
if !ERRORLEVEL! neq 0 (
    echo Deployment apply failed
    exit /b 1
)
kubectl apply -f "!WORK_DIR!\kubernetes\service.yaml"
if !ERRORLEVEL! neq 0 (
    echo Service apply failed
    exit /b 1
)
kubectl apply -f "!WORK_DIR!\kubernetes\ingress.yaml"
if !ERRORLEVEL! neq 0 (
    echo Ingress apply failed
    exit /b 1
)

echo Waiting for the rollout ...
kubectl rollout status deployment/!NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo The rollout failed. Roll back with kubectl rollout undo deployment/!NAME! -n !NAMESPACE!
    echo Inspect with kubectl describe pods -n !NAMESPACE! and kubectl logs deployment/!NAME! -n !NAMESPACE!
    rmdir /s /q "!WORK_DIR!"
    exit /b 1
)

kubectl get pods,svc,ingress -n !NAMESPACE!
echo Application URL, the load balancer hostname of the ingress below with Host header !NAME!.example.com
kubectl get ingress !NAME!-ingress -n !NAMESPACE! -o jsonpath="{.status.loadBalancer.ingress[0].hostname}"
echo.
echo To roll back use kubectl rollout undo deployment/!NAME! -n !NAMESPACE!

rmdir /s /q "!WORK_DIR!"
endlocal
