@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: build-push.bat  -  Build and push the ResortsLite Docker image (Windows)
:: Usage: scripts\build-push.bat
:: Run from the repository root directory.
:: =============================================================================

set PROJECT_NAME=resortslite

echo ==============================================
echo   ResortsLite - Docker Build ^& Push
echo ==============================================

:: ---------- Registry selection ----------
echo.
echo Select container registry:
echo   1) Google Artifact Registry
echo   2) Docker Hub
set /p REGISTRY_CHOICE="Enter choice [1 or 2]: "

:: ---------- Image tag ----------
set /p RAW_TAG="Enter image tag (leave blank for 'latest'): "

:: Sanitise image name (already lowercase, safe)
set IMAGE_NAME=%PROJECT_NAME%

:: Sanitise tag
if "!RAW_TAG!"=="" (
    set IMAGE_TAG=latest
) else (
    set IMAGE_TAG=!RAW_TAG!
)

:: ---------- Registry-specific setup ----------
if "!REGISTRY_CHOICE!"=="1" (
    echo.
    echo --- Google Artifact Registry ---
    set /p GCP_PROJECT="GCP Project ID: "
    set /p GCP_REGION="GCP Region (e.g. us-central1): "
    set /p AR_REPO="Artifact Registry repository name: "

    echo.
    echo Authenticating with Google Cloud...
    gcloud auth login --quiet
    if !ERRORLEVEL! neq 0 (echo gcloud auth login failed & exit /b 1)

    gcloud config set project !GCP_PROJECT!
    if !ERRORLEVEL! neq 0 (echo gcloud config set project failed & exit /b 1)

    gcloud auth configure-docker !GCP_REGION!-docker.pkg.dev --quiet
    if !ERRORLEVEL! neq 0 (echo Artifact Registry login failed & exit /b 1)

    set FULL_IMAGE_NAME=!GCP_REGION!-docker.pkg.dev/!GCP_PROJECT!/!AR_REPO!/!IMAGE_NAME!:!IMAGE_TAG!

) else if "!REGISTRY_CHOICE!"=="2" (
    echo.
    echo --- Docker Hub ---
    set /p DOCKER_USERNAME="Docker Hub username: "
    set /p DOCKER_PASSWORD="Docker Hub password/token: "

    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (echo Docker Hub login failed & exit /b 1)

    set FULL_IMAGE_NAME=!DOCKER_USERNAME!/!IMAGE_NAME!:!IMAGE_TAG!

) else (
    echo Invalid choice. Exiting.
    exit /b 1
)

:: ---------- Build ----------
echo.
echo Building Docker image: !FULL_IMAGE_NAME!
docker build -f Dockerfile -t !FULL_IMAGE_NAME! .
if !ERRORLEVEL! neq 0 (echo Docker build failed & exit /b 1)

echo.
echo Pushing image: !FULL_IMAGE_NAME!
docker push !FULL_IMAGE_NAME!
if !ERRORLEVEL! neq 0 (echo Docker push failed & exit /b 1)

echo.
echo ==============================================
echo   Image pushed successfully!
echo   !FULL_IMAGE_NAME!
echo ==============================================

endlocal
