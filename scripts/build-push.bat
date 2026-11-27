@echo off
setlocal enabledelayedexpansion

rem Builds the Resortlite_Mono1 image and pushes it to AWS ECR or Docker Hub.
rem Run from the repository root with scripts\build-push.bat

set "PROJECT_NAME=Resortlite_Mono1"
set "TMP_OUT=%TEMP%\resortlite-mono1-build.txt"
powershell -NoProfile -Command "('!PROJECT_NAME!'.ToLower() -replace '[^a-z0-9]+','-').Trim('-')" > "!TMP_OUT!"
set /p IMAGE_NAME=<"!TMP_OUT!"
if "!IMAGE_NAME!"=="" set "IMAGE_NAME=resortlite-mono1"

echo === Build and push: !IMAGE_NAME! ===
echo Select the registry:
echo   1. AWS ECR (target platform)
echo   2. Docker Hub
set /p REGISTRY_CHOICE="Enter 1 or 2: "

if "!REGISTRY_CHOICE!"=="1" (
    set /p AWS_REGION="Enter the AWS region (for example us-east-1): "
    set /p AWS_ACCOUNT_ID="Enter the AWS account id: "
    set /p ECR_REPO="Enter the ECR repository name (Enter for !IMAGE_NAME!): "
    if "!ECR_REPO!"=="" set "ECR_REPO=!IMAGE_NAME!"
    set "REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
    set "REPO_PATH=!ECR_REPO!"
) else if "!REGISTRY_CHOICE!"=="2" (
    set /p DOCKER_USERNAME="Enter the Docker Hub username: "
    set /p DOCKER_PASSWORD="Enter the Docker Hub password or access token: "
    set /p DOCKER_REPO="Enter the repository name (Enter for !IMAGE_NAME!): "
    if "!DOCKER_REPO!"=="" set "DOCKER_REPO=!IMAGE_NAME!"
    set "REGISTRY_URL=docker.io"
    set "REPO_PATH=!DOCKER_USERNAME!/!DOCKER_REPO!"
) else (
    echo Invalid choice, enter 1 or 2
    exit /b 1
)

set /p IMAGE_TAG="Enter IMAGE_TAG (Enter for latest): "
if "!IMAGE_TAG!"=="" set "IMAGE_TAG=latest"
set "TAG_IN=!IMAGE_TAG!"
powershell -NoProfile -Command "$t=($env:TAG_IN -replace '[^A-Za-z0-9_.-]+','-').Trim('-'); if ($t -eq '') { $t='latest' }; $t" > "!TMP_OUT!"
set /p IMAGE_TAG=<"!TMP_OUT!"
if "!IMAGE_TAG!"=="" set "IMAGE_TAG=latest"
del "!TMP_OUT!" >nul 2>&1
set "FULL_IMAGE_NAME=!REGISTRY_URL!/!REPO_PATH!:!IMAGE_TAG!"

echo Building image !FULL_IMAGE_NAME! ...
docker build -f Dockerfile -t !FULL_IMAGE_NAME! .
if !ERRORLEVEL! neq 0 (
    echo Docker build failed
    exit /b 1
)
echo Build finished.

if "!REGISTRY_CHOICE!"=="1" (
    echo Logging in to AWS ECR ...
    aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
    if !ERRORLEVEL! neq 0 (
        echo ECR login failed
        exit /b 1
    )
    aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
    if !ERRORLEVEL! neq 0 (
        echo Creating ECR repository...
        aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
        if !ERRORLEVEL! neq 0 (
            echo ECR repository creation failed
            exit /b 1
        )
    )
) else (
    echo Logging in to Docker Hub ...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo Docker Hub login failed
        exit /b 1
    )
)

echo Pushing !FULL_IMAGE_NAME! ...
docker push !FULL_IMAGE_NAME!
if !ERRORLEVEL! neq 0 (
    echo Docker push failed
    exit /b 1
)
echo Push finished. Image URI !FULL_IMAGE_NAME!
echo Use this image URI with scripts\deploy-image.bat
endlocal
