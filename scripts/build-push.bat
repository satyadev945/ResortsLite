@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: build-push.bat — Build and push ResortsLite Docker image
:: Usage: scripts\build-push.bat
:: Run from repository root directory
:: ============================================================

set "PROJECT_NAME=resortslite"
set "DOCKERFILE_PATH=Dockerfile"

echo ==============================================
echo   ResortsLite - Docker Build ^& Push Script
echo ==============================================
echo.

:: ── Sanitize image name ──────────────────────────────────────
set "IMAGE_NAME=%PROJECT_NAME%"
echo Image name : !IMAGE_NAME!

:: ── Prompt for image tag ─────────────────────────────────────
set /p "INPUT_TAG=Enter image tag [latest]: "
if "!INPUT_TAG!"=="" set "INPUT_TAG=latest"
set "IMAGE_TAG=!INPUT_TAG!"
echo Image tag  : !IMAGE_TAG!
echo.

:: ── Registry selection ───────────────────────────────────────
echo Select container registry:
echo   1) AWS ECR
echo   2) Docker Hub
set /p "REGISTRY_CHOICE=Enter choice [1]: "
if "!REGISTRY_CHOICE!"=="" set "REGISTRY_CHOICE=1"

if "!REGISTRY_CHOICE!"=="1" goto :ecr_setup
if "!REGISTRY_CHOICE!"=="2" goto :dockerhub_setup
echo ERROR: Invalid registry choice '!REGISTRY_CHOICE!'.
exit /b 1

:: ── AWS ECR ──────────────────────────────────────────────────
:ecr_setup
echo.
echo -- AWS ECR Configuration --
set /p "AWS_REGION=Enter AWS region [us-east-1]: "
if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

set /p "AWS_ACCOUNT_ID=Enter AWS account ID: "
if "!AWS_ACCOUNT_ID!"=="" (
  echo ERROR: AWS account ID is required.
  exit /b 1
)

set "ECR_REPO=!IMAGE_NAME!"
set "REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
set "FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!"

echo.
echo Logging in to AWS ECR...
aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
if !ERRORLEVEL! neq 0 (
  echo ERROR: ECR login failed.
  exit /b 1
)

echo Checking ECR repository '!ECR_REPO!'...
aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
if !ERRORLEVEL! neq 0 (
  echo Creating ECR repository...
  aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
  if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to create ECR repository.
    exit /b 1
  )
)
echo ECR repository ready.
goto :build

:: ── Docker Hub ───────────────────────────────────────────────
:dockerhub_setup
echo.
echo -- Docker Hub Configuration --
set /p "DOCKER_USERNAME=Enter Docker Hub username: "
if "!DOCKER_USERNAME!"=="" (
  echo ERROR: Docker Hub username is required.
  exit /b 1
)

set /p "DOCKER_PASSWORD=Enter Docker Hub password/token: "
if "!DOCKER_PASSWORD!"=="" (
  echo ERROR: Docker Hub password is required.
  exit /b 1
)

set "FULL_IMAGE_NAME=!DOCKER_USERNAME!/!IMAGE_NAME!:!IMAGE_TAG!"

echo Logging in to Docker Hub...
echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
if !ERRORLEVEL! neq 0 (
  echo ERROR: Docker Hub login failed.
  exit /b 1
)
goto :build

:: ── Build Docker image ───────────────────────────────────────
:build
echo.
echo Full image : !FULL_IMAGE_NAME!
echo.
echo Building Docker image...
docker build -f "!DOCKERFILE_PATH!" -t "!FULL_IMAGE_NAME!" .
if !ERRORLEVEL! neq 0 (
  echo ERROR: Docker build failed.
  exit /b 1
)
echo Build successful.
echo.

:: ── Push Docker image ────────────────────────────────────────
echo Pushing image to registry...
docker push "!FULL_IMAGE_NAME!"
if !ERRORLEVEL! neq 0 (
  echo ERROR: Docker push failed.
  exit /b 1
)

echo.
echo ==============================================
echo   Image pushed successfully!
echo   !FULL_IMAGE_NAME!
echo ==============================================

endlocal
exit /b 0
