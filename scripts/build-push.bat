@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: build-push.bat — Build and push the ResortsLite Docker image (Windows)
:: Supports: AWS ECR and Docker Hub
:: Usage   : scripts\build-push.bat   (run from repository root)
:: =============================================================================

set "PROJECT_NAME=resortsLite"
set "DOCKERFILE_PATH=Dockerfile"
set "BUILD_CONTEXT=."

echo ==============================================
echo   ResortsLite -- Docker Build ^& Push
echo ==============================================
echo.

:: ---------------------------------------------------------------------------
:: Sanitise image name via PowerShell
:: ---------------------------------------------------------------------------
for /f "delims=" %%I in ('powershell -NoProfile -Command "$n='%PROJECT_NAME%'; $n=$n.ToLower() -replace '[^a-z0-9]+','-'; $n=$n.Trim('-'); Write-Output $n"') do set "IMAGE_NAME=%%I"

:: ---------------------------------------------------------------------------
:: Prompt for image tag
:: ---------------------------------------------------------------------------
set /p "IMAGE_TAG_INPUT=Enter image tag [latest]: "
if "!IMAGE_TAG_INPUT!"=="" set "IMAGE_TAG_INPUT=latest"
for /f "delims=" %%I in ('powershell -NoProfile -Command "$t='!IMAGE_TAG_INPUT!'; $t=$t.ToLower() -replace '[^a-z0-9._-]+','-'; $t=$t.Trim('-'); if($t -eq ''){$t='latest'}; Write-Output $t"') do set "IMAGE_TAG=%%I"
echo Using tag: !IMAGE_TAG!
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
:: Registry-specific configuration
:: ---------------------------------------------------------------------------
if "!REGISTRY_CHOICE!"=="1" (
    echo.
    echo --- AWS ECR Configuration ---
    set /p "AWS_REGION=AWS Region [us-east-1]: "
    if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

    set /p "ECR_REPO_INPUT=ECR Repository name [!IMAGE_NAME!]: "
    if "!ECR_REPO_INPUT!"=="" (
        set "ECR_REPO=!IMAGE_NAME!"
    ) else (
        set "ECR_REPO=!ECR_REPO_INPUT!"
    )

    echo Fetching AWS Account ID...
    for /f "delims=" %%A in ('aws sts get-caller-identity --query Account --output text') do set "ACCOUNT_ID=%%A"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to get AWS Account ID. Check your AWS credentials.
        exit /b 1
    )

    set "REGISTRY_URL=!ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
    set "FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!"

    echo.
    echo Logging in to ECR...
    aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: ECR login failed.
        exit /b 1
    )

    echo Checking ECR repository '!ECR_REPO!'...
    aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
    if !ERRORLEVEL! neq 0 (
        echo Creating ECR repository '!ECR_REPO!'...
        aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
        if !ERRORLEVEL! neq 0 (
            echo ERROR: Failed to create ECR repository.
            exit /b 1
        )
    )
    echo ECR repository ready.

) else if "!REGISTRY_CHOICE!"=="2" (
    echo.
    echo --- Docker Hub Configuration ---
    set /p "DOCKER_USERNAME=Docker Hub username: "
    set /p "DOCKER_PASSWORD=Docker Hub password/token: "
    set /p "DOCKER_NAMESPACE_INPUT=Docker Hub namespace/org [!DOCKER_USERNAME!]: "
    if "!DOCKER_NAMESPACE_INPUT!"=="" (
        set "DOCKER_NAMESPACE=!DOCKER_USERNAME!"
    ) else (
        set "DOCKER_NAMESPACE=!DOCKER_NAMESPACE_INPUT!"
    )

    set "FULL_IMAGE_NAME=!DOCKER_NAMESPACE!/!IMAGE_NAME!:!IMAGE_TAG!"

    echo Logging in to Docker Hub...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Docker Hub login failed.
        exit /b 1
    )

) else (
    echo ERROR: Invalid registry choice '!REGISTRY_CHOICE!'. Exiting.
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: Build
:: ---------------------------------------------------------------------------
echo.
echo Building Docker image: !FULL_IMAGE_NAME!
echo   Dockerfile : !DOCKERFILE_PATH!
echo   Context    : !BUILD_CONTEXT!
echo.
docker build -f !DOCKERFILE_PATH! -t !FULL_IMAGE_NAME! !BUILD_CONTEXT!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker build failed.
    exit /b 1
)
echo Build successful.

:: ---------------------------------------------------------------------------
:: Push
:: ---------------------------------------------------------------------------
echo.
echo Pushing image: !FULL_IMAGE_NAME!
docker push !FULL_IMAGE_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker push failed.
    exit /b 1
)

echo.
echo ==============================================
echo   Image pushed successfully!
echo   !FULL_IMAGE_NAME!
echo ==============================================

endlocal
