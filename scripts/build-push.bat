@echo off
setlocal enabledelayedexpansion

REM Run from the repository root (docker build context is ".")
set "IMAGE_NAME=resortslite"
echo === Build and push !IMAGE_NAME! ===

set "IMAGE_TAG="
set /p IMAGE_TAG="Enter image tag [latest]: "
if "!IMAGE_TAG!"=="" set "IMAGE_TAG=latest"

REM Sanitize tag: lowercase, hyphenate specials, trim hyphens
set "RAW_TAG=!IMAGE_TAG!"
powershell -NoProfile -Command "$t=($env:RAW_TAG).ToLower() -replace '[^a-z0-9._-]+','-'; $t=$t.Trim('-'); if ([string]::IsNullOrEmpty($t)) { $t='latest' }; Set-Content -Path $env:TEMP\resortslite_tag.txt -Value $t -NoNewline"
set /p IMAGE_TAG=<"%TEMP%\resortslite_tag.txt"
del "%TEMP%\resortslite_tag.txt" >nul 2>&1
if "!IMAGE_TAG!"=="" set "IMAGE_TAG=latest"

echo Select registry:
echo   1. AWS ECR
echo   2. Docker Hub
set "REGISTRY_CHOICE="
set /p REGISTRY_CHOICE="Choice [1/2]: "

if "!REGISTRY_CHOICE!"=="1" goto ecr
if "!REGISTRY_CHOICE!"=="2" goto dockerhub
echo Invalid choice.
exit /b 1

:ecr
set "AWS_REGION="
set "AWS_ACCOUNT_ID="
set "ECR_REPO="
set /p AWS_REGION="Enter AWS region (e.g. us-east-1): "
set /p AWS_ACCOUNT_ID="Enter AWS account ID: "
set /p ECR_REPO="Enter ECR repository name [!IMAGE_NAME!]: "
if "!ECR_REPO!"=="" set "ECR_REPO=!IMAGE_NAME!"
if "!AWS_REGION!"=="" (
  echo AWS region is required.
  exit /b 1
)
if "!AWS_ACCOUNT_ID!"=="" (
  echo AWS account ID is required.
  exit /b 1
)
set "REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
set "FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!"

echo Logging in to ECR...
aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
if !ERRORLEVEL! neq 0 (
  echo ECR login failed
  exit /b 1
)

echo Ensuring ECR repository exists...
aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
if !ERRORLEVEL! neq 0 (
  echo Creating ECR repository...
  aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
  if !ERRORLEVEL! neq 0 (
    echo ECR repository creation failed
    exit /b 1
  )
)
goto build

:dockerhub
set "DOCKER_USERNAME="
set "DOCKER_PASSWORD="
set "DOCKER_REPO="
set /p DOCKER_USERNAME="Enter Docker Hub username: "
set /p DOCKER_PASSWORD="Enter Docker Hub password or access token: "
set /p DOCKER_REPO="Enter repository name [!IMAGE_NAME!]: "
if "!DOCKER_REPO!"=="" set "DOCKER_REPO=!IMAGE_NAME!"
if "!DOCKER_USERNAME!"=="" (
  echo Docker Hub username is required.
  exit /b 1
)
set "FULL_IMAGE_NAME=!DOCKER_USERNAME!/!DOCKER_REPO!:!IMAGE_TAG!"

echo Logging in to Docker Hub...
echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
if !ERRORLEVEL! neq 0 (
  echo Docker Hub login failed
  exit /b 1
)
goto build

:build
echo Building image !FULL_IMAGE_NAME! ...
docker build -f Dockerfile -t !FULL_IMAGE_NAME! .
if !ERRORLEVEL! neq 0 (
  echo Docker build failed
  exit /b 1
)

echo Pushing image !FULL_IMAGE_NAME! ...
docker push !FULL_IMAGE_NAME!
if !ERRORLEVEL! neq 0 (
  echo Docker push failed
  exit /b 1
)

echo Done. Image pushed: !FULL_IMAGE_NAME!
echo Use this URI with scripts\deploy-image.bat
endlocal
