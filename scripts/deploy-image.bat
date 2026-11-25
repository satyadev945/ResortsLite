@echo off & setlocal enabledelayedexpansion

cd /d "%~dp0.."

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"

echo === ResortsLite - Deploy to AWS EKS ===

where aws >nul 2>&1
if !ERRORLEVEL! neq 0 (
  echo ERROR: aws cli is not installed
  exit /b 1
)
where kubectl >nul 2>&1
if !ERRORLEVEL! neq 0 (
  echo ERROR: kubectl is not installed
  exit /b 1
)

set "AWS_REGION="
set /p AWS_REGION="AWS region [us-east-1]: "
if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"
set "CLUSTER_NAME="
set /p CLUSTER_NAME="EKS cluster name: "
if "!CLUSTER_NAME!"=="" (
  echo ERROR: cluster name is required
  exit /b 1
)
set "IMAGE_URI="
set /p IMAGE_URI="Docker image URI (registry/repo:tag): "
if "!IMAGE_URI!"=="" (
  echo ERROR: image URI is required
  exit /b 1
)

echo Application configuration - press Enter to accept defaults or skip
set "SPRING_DATASOURCE_URL="
set /p SPRING_DATASOURCE_URL="Enter value for SPRING_DATASOURCE_URL (or press Enter to skip): "
if "!SPRING_DATASOURCE_URL!"=="" set "SPRING_DATASOURCE_URL=jdbc:sqlserver://db-host:1433;databaseName=ResortsDB;encrypt=false;trustServerCertificate=true"
set "SPRING_DATASOURCE_USERNAME="
set /p SPRING_DATASOURCE_USERNAME="Enter value for SPRING_DATASOURCE_USERNAME (or press Enter to skip): "
set "SPRING_DATASOURCE_PASSWORD="
set /p SPRING_DATASOURCE_PASSWORD="Enter value for SPRING_DATASOURCE_PASSWORD (or press Enter to skip): "
set "REDIS_HOST="
set /p REDIS_HOST="Enter value for REDIS_HOST (or press Enter to skip): "
if "!REDIS_HOST!"=="" set "REDIS_HOST=localhost"
set "REDIS_PORT="
set /p REDIS_PORT="Enter value for REDIS_PORT (or press Enter to skip): "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"
set "REDIS_PASSWORD="
set /p REDIS_PASSWORD="Enter value for REDIS_PASSWORD (or press Enter to skip): "
set "REDIS_SSL_ENABLED="
set /p REDIS_SSL_ENABLED="Enter value for REDIS_SSL_ENABLED (or press Enter to skip): "
if "!REDIS_SSL_ENABLED!"=="" set "REDIS_SSL_ENABLED=false"
set "PAYMENT_API_URL="
set /p PAYMENT_API_URL="Enter value for PAYMENT_API_URL (or press Enter to skip): "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-svc:9090/payments/charge"

rem Work on a temp copy so templates keep their placeholders
set "WORK_DIR=%TEMP%\resortslite-k8s-%RANDOM%"
mkdir "!WORK_DIR!"
copy /y kubernetes\*.yaml "!WORK_DIR!\" >nul
if !ERRORLEVEL! neq 0 (
  echo ERROR: failed to copy manifests
  exit /b 1
)

for %%F in ("!WORK_DIR!\*.yaml") do (
  powershell -NoProfile -Command "$p='%%~fF'; $c=Get-Content -Raw $p; $c=$c.Replace('{{IMAGE_URI}}',$env:IMAGE_URI).Replace('{{NAMESPACE}}',$env:NAMESPACE).Replace('{{SPRING_DATASOURCE_URL}}',$env:SPRING_DATASOURCE_URL).Replace('{{REDIS_HOST}}',$env:REDIS_HOST).Replace('{{REDIS_PORT}}',$env:REDIS_PORT).Replace('{{REDIS_SSL_ENABLED}}',$env:REDIS_SSL_ENABLED).Replace('{{PAYMENT_API_URL}}',$env:PAYMENT_API_URL); [IO.File]::WriteAllText($p,$c)"
  if !ERRORLEVEL! neq 0 (
    echo ERROR: placeholder replacement failed
    exit /b 1
  )
)

echo Configuring kubectl...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
  echo ERROR: failed to update kubeconfig
  exit /b 1
)
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
  echo ERROR: cannot reach cluster
  exit /b 1
)

echo Applying namespace...
kubectl apply -f "!WORK_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 exit /b 1

echo Creating or updating secret...
kubectl create secret generic resortslite-secrets -n !NAMESPACE! "--from-literal=SPRING_DATASOURCE_USERNAME=!SPRING_DATASOURCE_USERNAME!" "--from-literal=SPRING_DATASOURCE_PASSWORD=!SPRING_DATASOURCE_PASSWORD!" "--from-literal=REDIS_PASSWORD=!REDIS_PASSWORD!" --dry-run=client -o yaml > "!WORK_DIR!\secret.yml"
if !ERRORLEVEL! neq 0 (
  echo ERROR: secret generation failed
  exit /b 1
)
kubectl apply -f "!WORK_DIR!\secret.yml"
if !ERRORLEVEL! neq 0 exit /b 1

echo Applying deployment, service, ingress...
kubectl apply -f "!WORK_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 exit /b 1
kubectl apply -f "!WORK_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 exit /b 1
kubectl apply -f "!WORK_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 exit /b 1

echo Waiting for rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
  echo ERROR: rollout failed. To roll back run:
  echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
  exit /b 1
)

kubectl get pods,svc,ingress -n !NAMESPACE!

set "INGRESS_HOST="
for /f "usebackq delims=" %%H in (`kubectl get ingress resortslite-ingress -n !NAMESPACE! -o jsonpath^="{.status.loadBalancer.ingress[0].hostname}" 2^>nul`) do set "INGRESS_HOST=%%H"
if not "!INGRESS_HOST!"=="" (
  echo Application URL: http://!INGRESS_HOST!
) else (
  echo Ingress address not yet assigned. Check: kubectl get ingress -n !NAMESPACE!
)

rmdir /s /q "!WORK_DIR!"
endlocal
