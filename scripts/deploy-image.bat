@echo off
setlocal enabledelayedexpansion

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"

if not exist "kubernetes" (
  echo ERROR: kubernetes directory not found. Run from the project root: scripts\deploy-image.bat
  exit /b 1
)

where aws >nul 2>&1
if !ERRORLEVEL! neq 0 (
  echo ERROR: aws CLI is not installed or not in PATH.
  exit /b 1
)
where kubectl >nul 2>&1
if !ERRORLEVEL! neq 0 (
  echo ERROR: kubectl is not installed or not in PATH.
  exit /b 1
)

set "AWS_REGION="
set "CLUSTER_NAME="
set "IMAGE_URI="
set /p AWS_REGION="Enter AWS region, for example us-east-1: "
set /p CLUSTER_NAME="Enter EKS cluster name: "
set /p IMAGE_URI="Enter Docker image URI, registry/repo:tag: "
if "!AWS_REGION!"=="" goto missing
if "!CLUSTER_NAME!"=="" goto missing
if "!IMAGE_URI!"=="" goto missing

echo --- Application configuration ---
set "SPRING_DATASOURCE_URL="
set "SPRING_DATASOURCE_USERNAME="
set "SPRING_DATASOURCE_PASSWORD="
set "REDIS_HOST="
set "REDIS_PORT="
set "REDIS_PASSWORD="
set "REDIS_SSL_ENABLED="
set "INGRESS_HOST="
set /p SPRING_DATASOURCE_URL="Enter value for SPRING_DATASOURCE_URL: "
set /p SPRING_DATASOURCE_USERNAME="Enter value for SPRING_DATASOURCE_USERNAME: "
set /p SPRING_DATASOURCE_PASSWORD="Enter value for SPRING_DATASOURCE_PASSWORD: "
set /p REDIS_HOST="Enter value for REDIS_HOST, the ElastiCache endpoint: "
set /p REDIS_PORT="Enter value for REDIS_PORT, or press Enter for 6379: "
set /p REDIS_PASSWORD="Enter value for REDIS_PASSWORD, or press Enter to skip: "
set /p REDIS_SSL_ENABLED="Enter value for REDIS_SSL_ENABLED true or false, Enter for false: "
set /p INGRESS_HOST="Enter ingress host, Enter for resortslite.example.com: "
if "!SPRING_DATASOURCE_URL!"=="" goto missing
if "!SPRING_DATASOURCE_USERNAME!"=="" goto missing
if "!REDIS_HOST!"=="" goto missing
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"
if "!REDIS_SSL_ENABLED!"=="" set "REDIS_SSL_ENABLED=false"
if "!INGRESS_HOST!"=="" set "INGRESS_HOST=resortslite.example.com"

REM Render manifests into a temp dir so the templates keep their placeholders
set "WORK_DIR=%TEMP%\resortslite-k8s-%RANDOM%"
mkdir "!WORK_DIR!"
copy /y kubernetes\*.yaml "!WORK_DIR!\" >nul
if !ERRORLEVEL! neq 0 (
  echo ERROR: could not copy manifests.
  exit /b 1
)

REM Values are read from environment variables by PowerShell (no cmd parsing of special characters)
powershell -NoProfile -Command "$q=[string][char]34; foreach($f in Get-ChildItem -Path $env:WORK_DIR -Filter *.yaml){ $c=[IO.File]::ReadAllText($f.FullName); foreach($k in 'IMAGE_URI','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD','REDIS_SSL_ENABLED','INGRESS_HOST'){ $v=[Environment]::GetEnvironmentVariable($k); if($v -eq $null){$v=''}; $v=$v.Replace('\','\\').Replace($q,'\'+$q); $c=$c.Replace('{{'+$k+'}}',$v) }; [IO.File]::WriteAllText($f.FullName,$c) }"
if !ERRORLEVEL! neq 0 (
  echo ERROR: manifest rendering failed.
  exit /b 1
)

echo Configuring kubectl for cluster !CLUSTER_NAME!...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 goto failed

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 goto failed

echo Applying manifests...
kubectl apply -f "!WORK_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 goto failed
kubectl apply -f "!WORK_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 goto failed
kubectl apply -f "!WORK_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 goto failed
kubectl apply -f "!WORK_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 goto failed

echo Waiting for rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 goto failed

echo Deployed resources:
kubectl get pods,svc,ingress -n !NAMESPACE!

echo Ingress address, may take a few minutes to appear:
kubectl get ingress resortslite-ingress -n !NAMESPACE! -o jsonpath="{.status.loadBalancer.ingress[0].hostname}"
echo.
echo Rollback if needed: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
rmdir /s /q "!WORK_DIR!" >nul 2>&1
endlocal
exit /b 0

:missing
echo ERROR: required values are missing.
exit /b 1

:failed
echo.
echo Deployment failed. To roll back run:
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
echo To inspect: kubectl describe pods -n !NAMESPACE!
rmdir /s /q "!WORK_DIR!" >nul 2>&1
exit /b 1
