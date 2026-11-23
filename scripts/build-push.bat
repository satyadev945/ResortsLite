@echo off
setlocal enabledelayedexpansion

set "PROJECT_NAME=ResortsLite"
set "IMAGE_NAME=resortslite"

echo === Build and push: !IMAGE_NAME! ===
echo Select registry type:
echo   1. Azure Container Registry - ACR
echo   2. Docker Hub
set /p REGISTRY_CHOICE="Enter choice [1-2]: "

set "IMAGE_TAG="
set /p IMAGE_TAG="Enter image tag [latest]: "
set "CLEAN_TAG="
if "!IMAGE_TAG!"=="" goto :tag_done
powershell -NoProfile -Command "$t = $env:IMAGE_TAG.ToLower(); $t = $t -replace '[^a-z0-9._-]+','-'; $t = $t -replace '^-+|-+$',''; Write-Output $t" > "%TEMP%\resortslite-tag.txt"
set /p CLEAN_TAG=<"%TEMP%\resortslite-tag.txt"
del "%TEMP%\resortslite-tag.txt" >nul 2>&1
:tag_done
if "!CLEAN_TAG!"=="" set "CLEAN_TAG=latest"

if "!REGISTRY_CHOICE!"=="1" goto :acr
if "!REGISTRY_CHOICE!"=="2" goto :dockerhub
echo ERROR: Invalid choice. Enter 1 or 2.
exit /b 1

:acr
set /p ACR_NAME="Enter ACR name without .azurecr.io: "
if "!ACR_NAME!"=="" (
  echo ERROR: ACR name is required
  exit /b 1
)
echo Logging in to ACR !ACR_NAME! ...
az acr login --name !ACR_NAME!
if !ERRORLEVEL! neq 0 (
  echo ACR login failed
  exit /b 1
)
set "FULL_IMAGE_NAME=!ACR_NAME!.azurecr.io/!IMAGE_NAME!:!CLEAN_TAG!"
goto :build

:dockerhub
set /p DOCKER_USERNAME="Enter Docker Hub username: "
set /p DOCKER_PASSWORD="Enter Docker Hub password or access token: "
if "!DOCKER_USERNAME!"=="" (
  echo ERROR: Docker Hub username is required
  exit /b 1
)
echo Logging in to Docker Hub ...
echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
if !ERRORLEVEL! neq 0 (
  echo Docker Hub login failed
  exit /b 1
)
set "FULL_IMAGE_NAME=!DOCKER_USERNAME!/!IMAGE_NAME!:!CLEAN_TAG!"
goto :build

:build
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

echo.
echo SUCCESS: image pushed to !FULL_IMAGE_NAME!
echo Use this URI when running scripts\deploy-image.bat
endlocal
