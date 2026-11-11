@echo off
setlocal enabledelayedexpansion

rem Run from repository root: scripts\build-push.bat
set "PROJECT_NAME=resortslite"
set "TMPF=%TEMP%\resortslite_name.txt"

powershell -NoProfile -Command "('%PROJECT_NAME%').ToLower() -replace '[^a-z0-9]+','-' -replace '^-+','' -replace '-+$',''" > "!TMPF!"
set /p IMAGE_NAME=<"!TMPF!"
del "!TMPF!" >nul 2>&1

echo === Build and push image: !IMAGE_NAME! ===

set "IMAGE_TAG="
set /p IMAGE_TAG="Enter image tag [latest]: "
if "!IMAGE_TAG!"=="" set "IMAGE_TAG=latest"
set "TAG_IN=!IMAGE_TAG!"
powershell -NoProfile -Command "$t=$env:TAG_IN.ToLower() -replace '[^a-z0-9._]+','-' -replace '^-+','' -replace '-+$',''; if ($t -eq '') { $t='latest' }; $t" > "!TMPF!"
set /p IMAGE_TAG=<"!TMPF!"
del "!TMPF!" >nul 2>&1

echo Select registry type:
echo   1. Azure Container Registry (ACR)
echo   2. Docker Hub
set "REGISTRY_CHOICE="
set /p REGISTRY_CHOICE="Choice [1/2]: "

if "!REGISTRY_CHOICE!"=="1" (
    set "ACR_NAME="
    set /p ACR_NAME="Enter ACR name (without .azurecr.io): "
    if "!ACR_NAME!"=="" (
        echo ACR name is required
        exit /b 1
    )
    set "FULL_IMAGE_NAME=!ACR_NAME!.azurecr.io/!IMAGE_NAME!:!IMAGE_TAG!"
    echo Logging in to ACR...
    az acr login --name !ACR_NAME!
    if !ERRORLEVEL! neq 0 (
        echo ACR login failed
        exit /b 1
    )
) else if "!REGISTRY_CHOICE!"=="2" (
    set "DOCKER_USERNAME="
    set /p DOCKER_USERNAME="Enter Docker Hub username: "
    if "!DOCKER_USERNAME!"=="" (
        echo Docker Hub username is required
        exit /b 1
    )
    set "DOCKER_PASSWORD="
    set /p DOCKER_PASSWORD="Enter Docker Hub password or token: "
    set "FULL_IMAGE_NAME=!DOCKER_USERNAME!/!IMAGE_NAME!:!IMAGE_TAG!"
    echo Logging in to Docker Hub...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo Docker Hub login failed
        exit /b 1
    )
) else (
    echo Invalid choice
    exit /b 1
)

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

echo === Done ===
echo Image pushed: !FULL_IMAGE_NAME!
echo Use this URI with scripts\deploy-image.bat
endlocal
