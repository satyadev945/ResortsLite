@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: build-push.bat — Build and push Docker image for
:: Gitpul Resort MonoCMP (Spring Boot / Java 8)
:: ============================================================

set "PROJECT_NAME=gitpul-resort-monocmp"
set "DOCKERFILE_PATH=Dockerfile"

echo ==============================================
echo   Gitpul Resort MonoCMP -- Build ^& Push
echo ==============================================

:: ---- Prompt for image tag --------------------------------
set /p IMAGE_TAG_INPUT="Enter image tag [latest]: "
if "!IMAGE_TAG_INPUT!"=="" (
    set "IMAGE_TAG=latest"
) else (
    set "IMAGE_TAG=!IMAGE_TAG_INPUT!"
)
echo Using image tag: !IMAGE_TAG!

:: ---- Registry selection ----------------------------------
echo.
echo Select container registry:
echo   1) AWS ECR
echo   2) Docker Hub
set /p REGISTRY_CHOICE="Enter choice [1]: "
if "!REGISTRY_CHOICE!"=="" set "REGISTRY_CHOICE=1"

if "!REGISTRY_CHOICE!"=="1" goto :ecr_setup
if "!REGISTRY_CHOICE!"=="2" goto :dockerhub_setup
echo Invalid choice. Exiting.
exit /b 1

:: ---- AWS ECR ---------------------------------------------
:ecr_setup
echo.
set /p AWS_REGION="Enter AWS Region (e.g. us-east-1): "
set /p AWS_ACCOUNT_ID="Enter AWS Account ID: "
set /p ECR_REPO_INPUT="Enter ECR repository name [!PROJECT_NAME!]: "
if "!ECR_REPO_INPUT!"=="" (
    set "ECR_REPO=!PROJECT_NAME!"
) else (
    set "ECR_REPO=!ECR_REPO_INPUT!"
)

set "REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
set "FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!"

echo.
echo Authenticating with AWS ECR...
aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
if !ERRORLEVEL! neq 0 (
    echo ECR login failed.
    exit /b 1
)

echo Ensuring ECR repository exists...
aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
if !ERRORLEVEL! neq 0 (
    echo Creating ECR repository...
    aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
        echo Failed to create ECR repository.
        exit /b 1
    )
)
goto :build_image

:: ---- Docker Hub ------------------------------------------
:dockerhub_setup
echo.
set /p DOCKER_USERNAME="Enter Docker Hub username: "
set /p DOCKER_PASSWORD="Enter Docker Hub password/token: "
set /p DOCKER_REPO_INPUT="Enter Docker Hub repository name [!PROJECT_NAME!]: "
if "!DOCKER_REPO_INPUT!"=="" (
    set "DOCKER_REPO=!PROJECT_NAME!"
) else (
    set "DOCKER_REPO=!DOCKER_REPO_INPUT!"
)

set "FULL_IMAGE_NAME=!DOCKER_USERNAME!/!DOCKER_REPO!:!IMAGE_TAG!"

echo Authenticating with Docker Hub...
echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
if !ERRORLEVEL! neq 0 (
    echo Docker Hub login failed.
    exit /b 1
)
goto :build_image

:: ---- Build Docker image ----------------------------------
:build_image
echo.
echo Building Docker image: !FULL_IMAGE_NAME!
docker build -f "!DOCKERFILE_PATH!" -t "!FULL_IMAGE_NAME!" .
if !ERRORLEVEL! neq 0 (
    echo Docker build failed.
    exit /b 1
)
echo Docker build succeeded.

:: ---- Push Docker image -----------------------------------
echo.
echo Pushing image: !FULL_IMAGE_NAME!
docker push "!FULL_IMAGE_NAME!"
if !ERRORLEVEL! neq 0 (
    echo Docker push failed.
    exit /b 1
)
echo Docker push succeeded.

echo.
echo ==============================================
echo   Image pushed successfully!
echo   !FULL_IMAGE_NAME!
echo ==============================================

endlocal
exit /b 0
