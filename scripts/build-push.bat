@echo off & setlocal enabledelayedexpansion

cd /d "%~dp0.."

echo === ResortsLite - Build and Push Docker Image ===

set "PROJECT_NAME="
set /p PROJECT_NAME="Project name [resortslite]: "
if "!PROJECT_NAME!"=="" set "PROJECT_NAME=resortslite"
set "IMAGE_NAME="
for /f "usebackq delims=" %%A in (`powershell -NoProfile -Command "$n='!PROJECT_NAME!'.ToLower() -replace '[^a-z0-9]+','-'; $n.Trim('-')"`) do set "IMAGE_NAME=%%A"
if "!IMAGE_NAME!"=="" set "IMAGE_NAME=resortslite"

set "IMAGE_TAG="
set /p IMAGE_TAG="Image tag [latest]: "
if "!IMAGE_TAG!"=="" set "IMAGE_TAG=latest"
set "CLEAN_TAG="
for /f "usebackq delims=" %%A in (`powershell -NoProfile -Command "$t='!IMAGE_TAG!'.ToLower() -replace '[^a-z0-9._]+','-'; $t.Trim('-')"`) do set "CLEAN_TAG=%%A"
if "!CLEAN_TAG!"=="" set "CLEAN_TAG=latest"
set "IMAGE_TAG=!CLEAN_TAG!"

echo Select registry:
echo   1. AWS ECR
echo   2. Docker Hub
set "REGISTRY_CHOICE="
set /p REGISTRY_CHOICE="Choice [1/2]: "

if "!REGISTRY_CHOICE!"=="1" goto ecr
if "!REGISTRY_CHOICE!"=="2" goto dockerhub
echo Invalid choice
exit /b 1

:ecr
set "AWS_REGION="
set /p AWS_REGION="AWS region [us-east-1]: "
if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"
set "AWS_ACCOUNT_ID="
set /p AWS_ACCOUNT_ID="AWS account ID: "
if "!AWS_ACCOUNT_ID!"=="" (
  echo AWS account ID is required
  exit /b 1
)
set "ECR_REPO="
set /p ECR_REPO="ECR repository name [!IMAGE_NAME!]: "
if "!ECR_REPO!"=="" set "ECR_REPO=!IMAGE_NAME!"
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
set /p DOCKER_USERNAME="Docker Hub username: "
set "DOCKER_PASSWORD="
set /p DOCKER_PASSWORD="Docker Hub password/token: "
set "DOCKER_REPO="
set /p DOCKER_REPO="Repository name [!IMAGE_NAME!]: "
if "!DOCKER_REPO!"=="" set "DOCKER_REPO=!IMAGE_NAME!"
set "FULL_IMAGE_NAME=!DOCKER_USERNAME!/!DOCKER_REPO!:!IMAGE_TAG!"

echo Logging in to Docker Hub...
echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
if !ERRORLEVEL! neq 0 (
  echo Docker Hub login failed
  exit /b 1
)
goto build

:build
echo Building image: !FULL_IMAGE_NAME!
docker build -f Dockerfile -t !FULL_IMAGE_NAME! .
if !ERRORLEVEL! neq 0 (
  echo Docker build failed
  exit /b 1
)

echo Pushing image: !FULL_IMAGE_NAME!
docker push !FULL_IMAGE_NAME!
if !ERRORLEVEL! neq 0 (
  echo Docker push failed
  exit /b 1
)

echo Done. Image pushed: !FULL_IMAGE_NAME!
echo Use this URI with scripts\deploy-image.bat
endlocal
