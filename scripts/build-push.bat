@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: build-push.bat — Build and push the ResortsLite Docker image (Windows)
:: Supports: AWS ECR  |  Docker Hub
:: Usage   : scripts\build-push.bat   (run from repository root)
:: =============================================================================

set "PROJECT_NAME=resortsLite"
set "DOCKERFILE_PATH=Dockerfile"

echo ==============================================
echo   ResortsLite -- Docker Build ^& Push
echo ==============================================
echo.

:: ---------------------------------------------------------------------------
:: Sanitise image name using PowerShell
:: ---------------------------------------------------------------------------
for /f "delims=" %%i in ('powershell -NoProfile -Command "$n = 'resortsLite'.ToLower() -replace '[^a-z0-9]','-'; $n = $n.Trim('-'); Write-Output $n"') do set "IMAGE_NAME=%%i"
echo Image name: !IMAGE_NAME!

:: ---------------------------------------------------------------------------
:: Prompt for image tag
:: ---------------------------------------------------------------------------
set /p "RAW_TAG=Enter image tag [latest]: "
if "!RAW_TAG!"=="" set "RAW_TAG=latest"
for /f "delims=" %%i in ('powershell -NoProfile -Command "$t = '!RAW_TAG!'.ToLower() -replace '[^a-z0-9._-]','-'; $t = $t.Trim('-'); if ($t -eq '') { $t = 'latest' }; Write-Output $t"') do set "IMAGE_TAG=%%i"
echo Image tag: !IMAGE_TAG!
echo.

:: ---------------------------------------------------------------------------
:: Registry selection
:: ---------------------------------------------------------------------------
echo Select container registry:
echo   1) AWS ECR
echo   2) Docker Hub
set /p "REGISTRY_CHOICE=Enter choice [1]: "
if "!REGISTRY_CHOICE!"=="" set "REGISTRY_CHOICE=1"

:: ---------------------------------------------------------------------------
:: AWS ECR
:: ---------------------------------------------------------------------------
if "!REGISTRY_CHOICE!"=="1" (
    echo.
    echo --- AWS ECR Configuration ---
    set /p "AWS_REGION=AWS Region [us-east-1]: "
    if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

    set /p "AWS_ACCOUNT_ID=AWS Account ID (leave blank to auto-detect): "
    if "!AWS_ACCOUNT_ID!"=="" (
        echo Fetching AWS Account ID from STS...
        for /f "delims=" %%a in ('aws sts get-caller-identity --query Account --output text') do set "AWS_ACCOUNT_ID=%%a"
    )

    set "ECR_REPO=!IMAGE_NAME!"
    set "REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
    set "FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!"

    echo.
    echo Logging in to ECR: !REGISTRY_URL!
    aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
    if !ERRORLEVEL! neq 0 (
        echo ECR login failed.
        exit /b 1
    )

    echo Checking / creating ECR repository: !ECR_REPO!
    aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
    if !ERRORLEVEL! neq 0 (
        echo Creating ECR repository: !ECR_REPO!
        aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
        if !ERRORLEVEL! neq 0 (
            echo Failed to create ECR repository.
            exit /b 1
        )
    )
    goto BUILD
)

:: ---------------------------------------------------------------------------
:: Docker Hub
:: ---------------------------------------------------------------------------
if "!REGISTRY_CHOICE!"=="2" (
    echo.
    echo --- Docker Hub Configuration ---
    set /p "DOCKER_USERNAME=Docker Hub username: "
    set /p "DOCKER_PASSWORD=Docker Hub password/token: "
    set /p "DOCKER_NAMESPACE=Docker Hub namespace [!DOCKER_USERNAME!]: "
    if "!DOCKER_NAMESPACE!"=="" set "DOCKER_NAMESPACE=!DOCKER_USERNAME!"

    set "FULL_IMAGE_NAME=!DOCKER_NAMESPACE!/!IMAGE_NAME!:!IMAGE_TAG!"

    echo Logging in to Docker Hub...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo Docker Hub login failed.
        exit /b 1
    )
    goto BUILD
)

echo Invalid choice. Exiting.
exit /b 1

:BUILD
:: ---------------------------------------------------------------------------
:: Build
:: ---------------------------------------------------------------------------
echo.
echo Building Docker image: !FULL_IMAGE_NAME!
echo Build context: . (repository root)
docker build -f "!DOCKERFILE_PATH!" -t "!FULL_IMAGE_NAME!" .
if !ERRORLEVEL! neq 0 (
    echo Docker build failed.
    exit /b 1
)

echo.
echo Build successful: !FULL_IMAGE_NAME!

:: ---------------------------------------------------------------------------
:: Push
:: ---------------------------------------------------------------------------
echo.
echo Pushing image to registry...
docker push "!FULL_IMAGE_NAME!"
if !ERRORLEVEL! neq 0 (
    echo Docker push failed.
    exit /b 1
)

echo.
echo ==============================================
echo   Image pushed successfully!
echo   !FULL_IMAGE_NAME!
echo ==============================================

endlocal
