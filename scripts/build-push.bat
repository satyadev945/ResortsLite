@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: build-push.bat — Build and push ResortsLite Docker image
:: Usage: scripts\build-push.bat
:: Run from repository root directory
:: ============================================================

set PROJECT_NAME=resortslite

echo ==============================================
echo   ResortsLite - Docker Build ^& Push
echo ==============================================
echo.

:: Sanitize image name using PowerShell
for /f "delims=" %%i in ('powershell -Command "\"resortslite\" -replace \"[^a-z0-9]\",\"-\" -replace \"^-+\",\"\" -replace \"-+$\",\"\""') do set IMAGE_NAME=%%i

:: Prompt for image tag
set /p IMAGE_TAG_INPUT="Enter image tag [latest]: "
if "!IMAGE_TAG_INPUT!"=="" (
    set IMAGE_TAG=latest
) else (
    for /f "delims=" %%t in ('powershell -Command "\"!IMAGE_TAG_INPUT!\" -replace \"[^a-z0-9._-]\",\"-\" -replace \"^-+\",\"\" -replace \"-+$\",\"\""') do set IMAGE_TAG=%%t
)
if "!IMAGE_TAG!"=="" set IMAGE_TAG=latest

echo.
echo Select container registry:
echo   1) AWS ECR
echo   2) Docker Hub
set /p REGISTRY_CHOICE="Enter choice [1]: "
if "!REGISTRY_CHOICE!"=="" set REGISTRY_CHOICE=1

echo.

:: ---- AWS ECR ----
if "!REGISTRY_CHOICE!"=="1" (
    set /p AWS_REGION="Enter AWS Region (e.g. us-east-1): "
    set /p AWS_ACCOUNT_ID="Enter AWS Account ID: "
    set /p ECR_REPO_INPUT="Enter ECR repository name [!IMAGE_NAME!]: "
    if "!ECR_REPO_INPUT!"=="" (
        set ECR_REPO=!IMAGE_NAME!
    ) else (
        set ECR_REPO=!ECR_REPO_INPUT!
    )

    set REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com
    set FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!

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
    )

:: ---- Docker Hub ----
) else if "!REGISTRY_CHOICE!"=="2" (
    set /p DOCKER_USERNAME="Enter Docker Hub username: "
    set /p DOCKER_PASSWORD="Enter Docker Hub password/token: "
    set /p DOCKERHUB_REPO_INPUT="Enter Docker Hub repository [!DOCKER_USERNAME!/!IMAGE_NAME!]: "
    if "!DOCKERHUB_REPO_INPUT!"=="" (
        set DOCKERHUB_REPO=!DOCKER_USERNAME!/!IMAGE_NAME!
    ) else (
        set DOCKERHUB_REPO=!DOCKERHUB_REPO_INPUT!
    )

    set FULL_IMAGE_NAME=!DOCKERHUB_REPO!:!IMAGE_TAG!

    echo.
    echo Authenticating with Docker Hub...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo Docker Hub login failed.
        exit /b 1
    )

) else (
    echo Invalid choice. Exiting.
    exit /b 1
)

echo.
echo Building Docker image: !FULL_IMAGE_NAME!
docker build -f Dockerfile -t "!FULL_IMAGE_NAME!" .
if !ERRORLEVEL! neq 0 (
    echo Docker build failed.
    exit /b 1
)

echo.
echo Pushing image: !FULL_IMAGE_NAME!
docker push "!FULL_IMAGE_NAME!"
if !ERRORLEVEL! neq 0 (
    echo Docker push failed.
    exit /b 1
)

echo.
echo ==============================================
echo   Build ^& Push Complete!
echo   Image: !FULL_IMAGE_NAME!
echo ==============================================

endlocal
