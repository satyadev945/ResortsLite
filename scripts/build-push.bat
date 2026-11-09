@echo off
setlocal enabledelayedexpansion

echo ============================================
echo   ResortsLite - Docker Build ^& Push Script
echo ============================================
echo.
echo Select container registry:
echo   1. Azure Container Registry (ACR)
echo   2. Docker Hub
echo.
set /p REGISTRY_CHOICE="Enter choice [1 or 2]: "

set /p IMAGE_TAG_INPUT="Enter image tag (press Enter for 'latest'): "
if "!IMAGE_TAG_INPUT!"=="" (
    set IMAGE_TAG=latest
) else (
    set IMAGE_TAG=!IMAGE_TAG_INPUT!
)

set PROJECT_NAME=resortslite
set IMAGE_NAME=resortslite

echo.

if "!REGISTRY_CHOICE!"=="1" (
    set /p ACR_NAME="Enter ACR name (e.g., myregistry): "
    set FULL_IMAGE_NAME=!ACR_NAME!.azurecr.io/!IMAGE_NAME!:!IMAGE_TAG!

    echo.
    echo Logging in to Azure Container Registry: !ACR_NAME! ...
    az acr login --name !ACR_NAME!
    if !ERRORLEVEL! neq 0 (
        echo ACR login failed. Exiting.
        exit /b 1
    )
    echo ACR login successful.

) else if "!REGISTRY_CHOICE!"=="2" (
    set /p DOCKER_USERNAME="Enter Docker Hub username: "
    set /p DOCKER_PASSWORD="Enter Docker Hub password: "
    set FULL_IMAGE_NAME=!DOCKER_USERNAME!/!IMAGE_NAME!:!IMAGE_TAG!

    echo.
    echo Logging in to Docker Hub ...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo Docker Hub login failed. Exiting.
        exit /b 1
    )
    echo Docker Hub login successful.

) else (
    echo Invalid choice. Exiting.
    exit /b 1
)

echo.
echo Building Docker image: !FULL_IMAGE_NAME!
echo Build context: . (repository root)
docker build -f Dockerfile -t !FULL_IMAGE_NAME! .
if !ERRORLEVEL! neq 0 (
    echo Docker build failed. Exiting.
    exit /b 1
)
echo Docker build successful.

echo.
echo Pushing image: !FULL_IMAGE_NAME! ...
docker push !FULL_IMAGE_NAME!
if !ERRORLEVEL! neq 0 (
    echo Docker push failed. Exiting.
    exit /b 1
)
echo Image pushed successfully.

echo.
echo ============================================
echo   Build ^& Push Complete
echo   Image: !FULL_IMAGE_NAME!
echo ============================================

endlocal
