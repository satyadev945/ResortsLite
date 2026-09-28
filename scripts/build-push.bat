@echo off
setlocal enabledelayedexpansion

REM ============================================================================
REM Build and Push Docker Image Script for ResortsLite (Windows)
REM Supports Google Artifact Registry and Docker Hub
REM ============================================================================

echo ============================================
echo ResortsLite - Docker Build and Push Script
echo ============================================
echo.

REM Project configuration
set PROJECT_NAME=resortslite

REM Sanitize project name for Docker tag (lowercase, hyphenate)
set IMAGE_NAME=%PROJECT_NAME%
for %%i in (A B C D E F G H I J K L M N O P Q R S T U V W X Y Z) do (
    set IMAGE_NAME=!IMAGE_NAME:%%i=%%i!
)
set IMAGE_NAME=%IMAGE_NAME: =-%
set IMAGE_NAME=%IMAGE_NAME:_=-%

REM Prompt for image tag
set /p IMAGE_TAG="Enter image tag (default: latest): "
if "!IMAGE_TAG!"=="" set IMAGE_TAG=latest

echo.
echo Select Docker Registry:
echo 1. Google Artifact Registry
echo 2. Docker Hub
set /p REGISTRY_CHOICE="Enter choice (1 or 2): "

if "!REGISTRY_CHOICE!"=="1" (
    REM Google Artifact Registry
    echo.
    echo === Google Artifact Registry Configuration ===
    set /p GCP_PROJECT="Enter GCP Project ID: "
    set /p GCP_REGION="Enter GCP Region (e.g., us-central1): "
    set /p AR_REPO="Enter Artifact Registry Repository Name: "
    
    set REGISTRY=!GCP_REGION!-docker.pkg.dev
    set FULL_IMAGE_NAME=!REGISTRY!/!GCP_PROJECT!/!AR_REPO!/!IMAGE_NAME!:!IMAGE_TAG!
    
    echo.
    echo Authenticating with Google Cloud...
    call gcloud auth login
    if !ERRORLEVEL! neq 0 (
        echo ERROR: GCloud authentication failed!
        exit /b 1
    )
    
    echo.
    echo Setting GCP project...
    call gcloud config set project "!GCP_PROJECT!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to set GCP project!
        exit /b 1
    )
    
    echo.
    echo Configuring Docker for Artifact Registry...
    call gcloud auth configure-docker !GCP_REGION!-docker.pkg.dev
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Artifact Registry authentication failed!
        exit /b 1
    )
    
) else if "!REGISTRY_CHOICE!"=="2" (
    REM Docker Hub
    echo.
    echo === Docker Hub Configuration ===
    set /p DOCKER_USERNAME="Enter Docker Hub username: "
    set /p DOCKER_PASSWORD="Enter Docker Hub password/token: "
    
    set FULL_IMAGE_NAME=!DOCKER_USERNAME!/!IMAGE_NAME!:!IMAGE_TAG!
    
    echo.
    echo Authenticating with Docker Hub...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Docker Hub authentication failed!
        exit /b 1
    )
    
) else (
    echo Invalid choice. Exiting.
    exit /b 1
)

echo.
echo ============================================
echo Building Docker image...
echo Image: !FULL_IMAGE_NAME!
echo ============================================

docker build -t "!FULL_IMAGE_NAME!" .
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker build failed!
    exit /b 1
)

echo.
echo ============================================
echo Pushing Docker image to registry...
echo ============================================

docker push "!FULL_IMAGE_NAME!"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker push failed!
    exit /b 1
)

echo.
echo ============================================
echo SUCCESS!
echo ============================================
echo Image pushed successfully: !FULL_IMAGE_NAME!
echo.
echo Next steps:
echo 1. Update kubernetes/deployment.yaml with the image URI
echo 2. Run deploy-image.bat to deploy to GKE
echo ============================================

endlocal
