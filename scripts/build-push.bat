@echo off
setlocal enabledelayedexpansion

REM Run from the repository root (docker build context is ".")
set "PROJECT_NAME=ResortsLite"
set "TMPF=%TEMP%\resortslite_sanitize.txt"

if not exist "Dockerfile" (
  echo ERROR: Dockerfile not found. Run this script from the project root: scripts\build-push.bat
  exit /b 1
)

REM Sanitize image name
set "IMAGE_NAME=resortslite"
powershell -NoProfile -Command "('!PROJECT_NAME!').ToLower() -replace '[^a-z0-9]+','-' -replace '^-+|-+$',''" > "!TMPF!"
set /p IMAGE_NAME=<"!TMPF!"

set "IMAGE_TAG="
set /p IMAGE_TAG="Enter image tag [latest]: "
if "!IMAGE_TAG!"=="" set "IMAGE_TAG=latest"
set "SAFE_TAG=latest"
powershell -NoProfile -Command "('!IMAGE_TAG!').ToLower() -replace '[^a-z0-9._-]+','-' -replace '^-+|-+$',''" > "!TMPF!"
set /p SAFE_TAG=<"!TMPF!"
if "!SAFE_TAG!"=="" set "SAFE_TAG=latest"
set "IMAGE_TAG=!SAFE_TAG!"
del "!TMPF!" >nul 2>&1
echo Using image name !IMAGE_NAME! and tag !IMAGE_TAG!

echo Select registry:
echo   1. AWS ECR
echo   2. Docker Hub
set "REGISTRY_CHOICE="
set /p REGISTRY_CHOICE="Enter choice 1 or 2: "

if "!REGISTRY_CHOICE!"=="1" goto ecr
if "!REGISTRY_CHOICE!"=="2" goto dockerhub
echo ERROR: Invalid choice.
exit /b 1

:ecr
set "AWS_REGION="
set "AWS_ACCOUNT_ID="
set /p AWS_REGION="Enter AWS region, for example us-east-1: "
set /p AWS_ACCOUNT_ID="Enter AWS account ID: "
if "!AWS_REGION!"=="" (
  echo ERROR: AWS region is required.
  exit /b 1
)
if "!AWS_ACCOUNT_ID!"=="" (
  echo ERROR: AWS account ID is required.
  exit /b 1
)
set "ECR_REPO=!IMAGE_NAME!"
set "REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
set "FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!"

echo Logging in to Amazon ECR...
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
set /p DOCKER_USERNAME="Enter Docker Hub username: "
set /p DOCKER_PASSWORD="Enter Docker Hub password or access token: "
if "!DOCKER_USERNAME!"=="" (
  echo ERROR: Docker Hub username is required.
  exit /b 1
)
if "!DOCKER_PASSWORD!"=="" (
  echo ERROR: Docker Hub password is required.
  exit /b 1
)
set "FULL_IMAGE_NAME=!DOCKER_USERNAME!/!IMAGE_NAME!:!IMAGE_TAG!"
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

echo SUCCESS: pushed !FULL_IMAGE_NAME!
echo Use this image URI with scripts\deploy-image.bat
endlocal
