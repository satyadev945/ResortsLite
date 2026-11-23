@echo off
setlocal enabledelayedexpansion

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "K8S_SRC=%~dp0..\kubernetes"

set /p AWS_REGION="AWS region (e.g. us-east-1): "
if "!AWS_REGION!"=="" (echo AWS region is required & exit /b 1)
set /p CLUSTER_NAME="EKS cluster name: "
if "!CLUSTER_NAME!"=="" (echo Cluster name is required & exit /b 1)
set /p IMAGE_URI="Docker image URI (registry/repo:tag): "
if "!IMAGE_URI!"=="" (echo Image URI is required & exit /b 1)

echo --- Application configuration (press Enter to accept default) ---
set /p SPRING_DATASOURCE_URL="SPRING_DATASOURCE_URL [jdbc:h2:mem:resortsdb;DB_CLOSE_DELAY=-1]: "
if "!SPRING_DATASOURCE_URL!"=="" set "SPRING_DATASOURCE_URL=jdbc:h2:mem:resortsdb;DB_CLOSE_DELAY=-1"
set /p SPRING_DATASOURCE_USERNAME="SPRING_DATASOURCE_USERNAME [sa]: "
if "!SPRING_DATASOURCE_USERNAME!"=="" set "SPRING_DATASOURCE_USERNAME=sa"
set /p SPRING_DATASOURCE_PASSWORD="SPRING_DATASOURCE_PASSWORD (Enter to skip): "
set /p REDIS_HOST="REDIS_HOST [localhost]: "
if "!REDIS_HOST!"=="" set "REDIS_HOST=localhost"
set /p REDIS_PORT="REDIS_PORT [6379]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"
set /p REDIS_SSL_ENABLED="REDIS_SSL_ENABLED [false]: "
if "!REDIS_SSL_ENABLED!"=="" set "REDIS_SSL_ENABLED=false"
set /p REDIS_PASSWORD="REDIS_PASSWORD (Enter to skip): "
set /p PAYMENT_API_URL="PAYMENT_API_URL [http://payment-svc:9090/payments/charge]: "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-svc:9090/payments/charge"

set "WORK_DIR=%TEMP%\resortslite-k8s-%RANDOM%"
mkdir "!WORK_DIR!"
copy /y "!K8S_SRC!\*.yaml" "!WORK_DIR!\" >nul

rem Values are passed through process environment variables to PowerShell
powershell -NoProfile -Command "Get-ChildItem '!WORK_DIR!\*.yaml' | ForEach-Object { $c = Get-Content $_.FullName -Raw; $c = $c.Replace('{{IMAGE_URI}}',$env:IMAGE_URI).Replace('{{NAMESPACE}}',$env:NAMESPACE).Replace('{{SPRING_DATASOURCE_URL}}',$env:SPRING_DATASOURCE_URL).Replace('{{SPRING_DATASOURCE_USERNAME}}',$env:SPRING_DATASOURCE_USERNAME).Replace('{{REDIS_HOST}}',$env:REDIS_HOST).Replace('{{REDIS_PORT}}',$env:REDIS_PORT).Replace('{{REDIS_SSL_ENABLED}}',$env:REDIS_SSL_ENABLED).Replace('{{PAYMENT_API_URL}}',$env:PAYMENT_API_URL); Set-Content -Path $_.FullName -Value $c -NoNewline }"
if !ERRORLEVEL! neq 0 (
  echo Failed to update manifests
  exit /b 1
)

echo Configuring kubectl...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
  echo Failed to configure kubectl
  exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
  echo Cannot connect to cluster
  exit /b 1
)

echo Applying namespace...
kubectl apply -f "!WORK_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 exit /b 1

echo Creating or updating secrets...
kubectl create secret generic resortslite-secrets -n !NAMESPACE! --from-literal=SPRING_DATASOURCE_PASSWORD="!SPRING_DATASOURCE_PASSWORD!" --from-literal=REDIS_PASSWORD="!REDIS_PASSWORD!" --dry-run=client -o yaml > "!WORK_DIR!\secret.out"
if !ERRORLEVEL! neq 0 (
  echo Secret generation failed
  exit /b 1
)
kubectl apply -f "!WORK_DIR!\secret.out"
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
  echo Rollout failed. To roll back run: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
  exit /b 1
)

kubectl get pods,svc,ingress -n !NAMESPACE!
echo Get the ALB address with: kubectl get ingress resortslite-ingress -n !NAMESPACE!

rmdir /s /q "!WORK_DIR!"
endlocal
