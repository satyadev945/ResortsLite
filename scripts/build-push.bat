@echo off
setlocal enabledelayedexpansion

:: =============================================================
:: build-push.bat — Build and push the ResortsLite Docker image
:: =============================================================

set "PROJECT_NAME=resortslite"
set "DOCKERFILE_PATH=Dockerfile"

echo ==============================================
echo   ResortsLite - Docker Build ^& Push
echo ==============================================

:: Sanitize image name via PowerShell
for /f "delims=" %%I in ('powershell -NoProfile -Command "$n = 'resortslite'; $n = $n.ToLower() -replace '[^a-z0-9]+','-'; $n = $n.Trim('-'); Write-Output $n"') do set "IMAGE_NAME=%%I"

:: Prompt for image tag
set /p "IMAGE_TAG_INPUT=Enter image tag [latest]: "
if "!IMAGE_TAG_INPUT!"=="" set "IMAGE_TAG_INPUT=latest"
for /f "delims=" %%T in ('powershell -NoProfile -Command "$t = '!IMAGE_TAG_INPUT!'; $t = $t.ToLower() -replace '[^a-z0-9._-]+','-'; $t = $t.Trim('-'); if ($t -eq '') { $t = 'latest' }; Write-Output $t"') do set "IMAGE_TAG=%%T"

echo.
echo Select container registry:
echo   1) AWS ECR
echo   2) Docker Hub
set /p "REGISTRY_CHOICE=Enter choice [1]: "
if "!REGISTRY_CHOICE!"=="" set "REGISTRY_CHOICE=1"

if "!REGISTRY_CHOICE!"=="1" goto :ecr
if "!REGISTRY_CHOICE!"=="2" goto :dockerhub
echo ERROR: Invalid registry choice.
exit /b 1

:ecr
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
echo Authenticating with AWS ECR...
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

echo.
echo Authenticating with Docker Hub...
echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker Hub login failed.
    exit /b 1
)
goto :build

:build
echo.
echo Building Docker image: !FULL_IMAGE_NAME!
docker build -f "!DOCKERFILE_PATH!" -t "!FULL_IMAGE_NAME!" .
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker build failed.
    exit /b 1
)

echo.
echo Pushing Docker image: !FULL_IMAGE_NAME!
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
