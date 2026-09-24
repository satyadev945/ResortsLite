@echo off
setlocal enabledelayedexpansion

:: =============================================================
:: build-push.bat — Build and push orcappdbmmono Docker image
:: =============================================================

set "PROJECT_NAME=orcappdbmmono"
set "IMAGE_NAME=orcappdbmmono"

echo ==============================================
echo   Build ^& Push: %PROJECT_NAME%
echo ==============================================
echo.

:: --- Registry selection ---
echo Select container registry:
echo   1) Azure Container Registry (ACR)
echo   2) Docker Hub
set /p REGISTRY_CHOICE="Enter choice [1 or 2]: "

if "!REGISTRY_CHOICE!"=="1" (
    set /p ACR_NAME="Enter ACR name (e.g. myregistry): "
    set "REGISTRY=!ACR_NAME!.azurecr.io"
    echo Logging in to ACR: !REGISTRY!
    az acr login --name !ACR_NAME!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: ACR login failed.
        exit /b 1
    )
) else if "!REGISTRY_CHOICE!"=="2" (
    set /p DOCKER_USERNAME="Enter Docker Hub username: "
    set /p DOCKER_PASSWORD="Enter Docker Hub password/token: "
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Docker Hub login failed.
        exit /b 1
    )
    set "REGISTRY=!DOCKER_USERNAME!"
) else (
    echo ERROR: Invalid choice. Exiting.
    exit /b 1
)

:: --- Image tag ---
set /p IMAGE_TAG="Enter image tag [default: latest]: "
if "!IMAGE_TAG!"=="" set "IMAGE_TAG=latest"

set "FULL_IMAGE_NAME=!REGISTRY!/!IMAGE_NAME!:!IMAGE_TAG!"

echo.
echo Building image: !FULL_IMAGE_NAME!
echo ----------------------------------------------

docker build -f Dockerfile -t "!FULL_IMAGE_NAME!" .
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
echo   SUCCESS: Image pushed successfully
echo   Image: !FULL_IMAGE_NAME!
echo ==============================================

endlocal
