@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: build-push.bat — Build and push Docker image for ResortsLite
:: Usage: scripts\build-push.bat
:: ============================================================

set "PROJECT_NAME=resortslite"
set "DOCKERFILE_PATH=Dockerfile"

echo ==============================================
echo   ResortsLite - Docker Build ^& Push Script
echo ==============================================
echo.

:: ---- Sanitize image name (PowerShell) ----
for /f "delims=" %%i in ('powershell -NoProfile -Command "$n = 'resortslite'; $n = $n.ToLower() -replace '[^a-z0-9]+','-'; $n = $n.Trim('-'); Write-Output $n"') do set "IMAGE_NAME=%%i"

:: ---- Prompt for image tag ----
set /p "INPUT_TAG=Enter image tag [latest]: "
if "!INPUT_TAG!"=="" set "INPUT_TAG=latest"
for /f "delims=" %%i in ('powershell -NoProfile -Command "$t = '!INPUT_TAG!'; $t = $t.ToLower() -replace '[^a-z0-9._-]+','-'; $t = $t.Trim('-'); if ($t -eq '') { $t = 'latest' }; Write-Output $t"') do set "IMAGE_TAG=%%i"
echo Using image tag: !IMAGE_TAG!
echo.

:: ---- Registry selection ----
echo Select container registry:
echo   1. AWS ECR
echo   2. Docker Hub
set /p "REGISTRY_CHOICE=Enter choice [1 or 2]: "
echo.

if "!REGISTRY_CHOICE!"=="1" goto :ecr
if "!REGISTRY_CHOICE!"=="2" goto :dockerhub
echo ERROR: Invalid registry choice. Exiting.
exit /b 1

:ecr
set /p "AWS_REGION=Enter AWS Region (e.g. us-east-1): "
set /p "AWS_ACCOUNT_ID=Enter AWS Account ID: "
set "ECR_REPO=!IMAGE_NAME!"
set "REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
set "FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!"

echo Logging in to AWS ECR...
aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
if !ERRORLEVEL! neq 0 (
    echo ERROR: ECR login failed.
    exit /b 1
)

echo Ensuring ECR repository exists...
aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
if !ERRORLEVEL! neq 0 (
    echo Creating ECR repository...
    aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECR repository.
        exit /b 1
    )
)
goto :build

:dockerhub
set /p "DOCKER_USERNAME=Enter Docker Hub username: "
set /p "DOCKER_PASSWORD=Enter Docker Hub password/token: "
set "REGISTRY_URL=docker.io"
set "FULL_IMAGE_NAME=!DOCKER_USERNAME!/!IMAGE_NAME!:!IMAGE_TAG!"

echo Logging in to Docker Hub...
echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker Hub login failed.
    exit /b 1
)
goto :build

:build
echo.
echo Building Docker image: !FULL_IMAGE_NAME!
echo ----------------------------------------------
docker build -f "!DOCKERFILE_PATH!" -t "!FULL_IMAGE_NAME!" .
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker build failed.
    exit /b 1
)

echo.
echo Pushing image: !FULL_IMAGE_NAME!
echo ----------------------------------------------
docker push "!FULL_IMAGE_NAME!"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker push failed.
    exit /b 1
)

echo.
echo ==============================================
echo   Build ^& Push Complete!
echo   Image: !FULL_IMAGE_NAME!
echo ==============================================

endlocal
exit /b 0
