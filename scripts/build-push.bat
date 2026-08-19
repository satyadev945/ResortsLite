@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: build-push.bat — Build and push the ResortsLite Docker image
:: ============================================================

set "PROJECT_NAME=resortslite"

echo ==============================================
echo   ResortsLite - Docker Build ^& Push
echo ==============================================
echo.

:: ---- Registry selection ----
echo Select target registry:
echo   1. Google Artifact Registry
echo   2. Docker Hub
echo.
set /p REGISTRY_CHOICE="Enter choice [1 or 2]: "

:: ---- Image tag ----
set /p RAW_TAG="Enter image tag (press Enter for 'latest'): "
if "!RAW_TAG!"=="" (
    set "IMAGE_TAG=latest"
) else (
    set "IMAGE_TAG=!RAW_TAG!"
)

echo.
echo Image name : !PROJECT_NAME!
echo Image tag  : !IMAGE_TAG!
echo.

:: ============================================================
:: Google Artifact Registry
:: ============================================================
if "!REGISTRY_CHOICE!"=="1" (
    set /p GCP_PROJECT="Enter GCP Project ID: "
    set /p GCP_REGION="Enter GCP Region (e.g. us-central1): "
    set /p AR_REPO="Enter Artifact Registry repository name: "

    set "FULL_IMAGE_NAME=!GCP_REGION!-docker.pkg.dev/!GCP_PROJECT!/!AR_REPO!/!PROJECT_NAME!:!IMAGE_TAG!"

    echo.
    echo Authenticating with Google Artifact Registry...
    gcloud auth configure-docker !GCP_REGION!-docker.pkg.dev --quiet
    if !ERRORLEVEL! neq 0 (
        echo Artifact Registry login failed
        exit /b 1
    )

    echo Building Docker image: !FULL_IMAGE_NAME!
    docker build -f Dockerfile -t "!FULL_IMAGE_NAME!" .
    if !ERRORLEVEL! neq 0 (
        echo Docker build failed
        exit /b 1
    )

    echo Pushing image to Artifact Registry...
    docker push "!FULL_IMAGE_NAME!"
    if !ERRORLEVEL! neq 0 (
        echo Docker push failed
        exit /b 1
    )

    echo.
    echo Image pushed successfully: !FULL_IMAGE_NAME!
    goto :end
)

:: ============================================================
:: Docker Hub
:: ============================================================
if "!REGISTRY_CHOICE!"=="2" (
    set /p DOCKER_USERNAME="Enter Docker Hub username: "
    set /p DOCKER_PASSWORD="Enter Docker Hub password/token: "

    set "FULL_IMAGE_NAME=!DOCKER_USERNAME!/!PROJECT_NAME!:!IMAGE_TAG!"

    echo.
    echo Authenticating with Docker Hub...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo Docker Hub login failed
        exit /b 1
    )

    echo Building Docker image: !FULL_IMAGE_NAME!
    docker build -f Dockerfile -t "!FULL_IMAGE_NAME!" .
    if !ERRORLEVEL! neq 0 (
        echo Docker build failed
        exit /b 1
    )

    echo Pushing image to Docker Hub...
    docker push "!FULL_IMAGE_NAME!"
    if !ERRORLEVEL! neq 0 (
        echo Docker push failed
        exit /b 1
    )

    echo.
    echo Image pushed successfully: !FULL_IMAGE_NAME!
    goto :end
)

echo Invalid choice. Exiting.
exit /b 1

:end
endlocal
