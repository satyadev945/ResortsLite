@echo off
setlocal enabledelayedexpansion

REM =============================================================================
REM build-push.bat — Build and push the ResortsLite Docker image (Windows)
REM Usage: scripts\build-push.bat
REM Run from the repository root (project root is the Docker build context)
REM =============================================================================

set "PROJECT_NAME=resortslite"
set "DOCKERFILE_PATH=Dockerfile"

echo ==============================================
echo   ResortsLite - Docker Build ^& Push
echo ==============================================

REM Sanitize image name via PowerShell
for /f "delims=" %%i in ('powershell -NoProfile -Command "$n = 'resortslite'; $n = $n.ToLower() -replace '[^a-z0-9]+','-'; $n = $n.Trim('-'); Write-Output $n"') do set "IMAGE_NAME=%%i"

REM Prompt for image tag
set /p "IMAGE_TAG_INPUT=Enter image tag [latest]: "
if "!IMAGE_TAG_INPUT!"=="" set "IMAGE_TAG_INPUT=latest"
for /f "delims=" %%i in ('powershell -NoProfile -Command "$t = '!IMAGE_TAG_INPUT!'; $t = $t.ToLower() -replace '[^a-z0-9._-]+','-'; $t = $t.Trim('-'); if ($t -eq '') { $t = 'latest' }; Write-Output $t"') do set "IMAGE_TAG=%%i"
echo Image tag: !IMAGE_TAG!

REM Registry selection
echo.
echo Select container registry:
echo   1. AWS ECR
echo   2. Docker Hub
set /p "REGISTRY_CHOICE=Enter choice [1]: "
if "!REGISTRY_CHOICE!"=="" set "REGISTRY_CHOICE=1"

if "!REGISTRY_CHOICE!"=="1" goto :ecr_flow
if "!REGISTRY_CHOICE!"=="2" goto :dockerhub_flow
echo Invalid choice. Exiting.
exit /b 1

REM ---- AWS ECR ----
:ecr_flow
set /p "AWS_REGION=Enter AWS region [us-east-1]: "
if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

for /f "delims=" %%i in ('aws sts get-caller-identity --query Account --output text') do set "ACCOUNT_ID=%%i"
if !ERRORLEVEL! neq 0 (
    echo Failed to retrieve AWS Account ID. Check AWS CLI configuration.
    exit /b 1
)
echo AWS Account ID: !ACCOUNT_ID!

set "ECR_REPO=!IMAGE_NAME!"
set "REGISTRY_URL=!ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
set "FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!"

echo.
echo Authenticating with ECR...
aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
if !ERRORLEVEL! neq 0 (
    echo ECR login failed. Exiting.
    exit /b 1
)

echo Ensuring ECR repository exists...
aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
if !ERRORLEVEL! neq 0 (
    echo Creating ECR repository: !ECR_REPO!
    aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
        echo Failed to create ECR repository. Exiting.
        exit /b 1
    )
)
goto :build_image

REM ---- Docker Hub ----
:dockerhub_flow
set /p "DOCKER_USERNAME=Enter Docker Hub username: "
set /p "DOCKER_PASSWORD=Enter Docker Hub password/token: "
set /p "DOCKER_REPO_INPUT=Enter Docker Hub repository name [!IMAGE_NAME!]: "
if "!DOCKER_REPO_INPUT!"=="" set "DOCKER_REPO_INPUT=!IMAGE_NAME!"
set "FULL_IMAGE_NAME=!DOCKER_USERNAME!/!DOCKER_REPO_INPUT!:!IMAGE_TAG!"

echo.
echo Authenticating with Docker Hub...
echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
if !ERRORLEVEL! neq 0 (
    echo Docker Hub login failed. Exiting.
    exit /b 1
)
goto :build_image

REM ---- Build and Push ----
:build_image
echo.
echo Building Docker image: !FULL_IMAGE_NAME!
docker build -f "!DOCKERFILE_PATH!" -t "!FULL_IMAGE_NAME!" .
if !ERRORLEVEL! neq 0 (
    echo Docker build failed. Exiting.
    exit /b 1
)

echo.
echo Pushing image: !FULL_IMAGE_NAME!
docker push "!FULL_IMAGE_NAME!"
if !ERRORLEVEL! neq 0 (
    echo Docker push failed. Exiting.
    exit /b 1
)

echo.
echo ==============================================
echo   Build ^& Push Complete!
echo   Image: !FULL_IMAGE_NAME!
echo ==============================================

endlocal
exit /b 0
