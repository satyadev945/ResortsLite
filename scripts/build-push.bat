@echo off
setlocal enabledelayedexpansion

REM ################################################################################
REM Build and Push Script for ResortsLite Docker Image (Windows)
REM Supports AWS ECR and Docker Hub registries
REM Usage: build-push.bat
REM ################################################################################

echo ==========================================
echo ResortsLite - Docker Build ^& Push Script
echo ==========================================
echo.

REM Project configuration
set PROJECT_NAME=resortslite

REM Sanitize project name for Docker tag (lowercase, hyphenate special chars)
set IMAGE_NAME=%PROJECT_NAME%
for %%i in (A B C D E F G H I J K L M N O P Q R S T U V W X Y Z) do (
    set IMAGE_NAME=!IMAGE_NAME:%%i=%%i!
)
set IMAGE_NAME=%IMAGE_NAME: =-%
set IMAGE_NAME=%IMAGE_NAME:_=-%

echo Project: %PROJECT_NAME%
echo Sanitized Image Name: %IMAGE_NAME%
echo.

REM Prompt for image tag
set /p IMAGE_TAG="Enter image tag (default: latest): "
if "!IMAGE_TAG!"=="" set IMAGE_TAG=latest

echo Image Tag: !IMAGE_TAG!
echo.

REM Registry selection
echo Select container registry:
echo 1. AWS ECR (Elastic Container Registry)
echo 2. Docker Hub
set /p REGISTRY_CHOICE="Enter choice (1 or 2): "

if "!REGISTRY_CHOICE!"=="1" (
    echo.
    echo === AWS ECR Configuration ===
    
    REM Prompt for AWS region
    set /p AWS_REGION="Enter AWS region (e.g., us-east-1): "
    if "!AWS_REGION!"=="" (
        echo Error: AWS region is required
        exit /b 1
    )
    
    REM Prompt for ECR repository name
    set /p ECR_REPO="Enter ECR repository name (default: %IMAGE_NAME%): "
    if "!ECR_REPO!"=="" set ECR_REPO=%IMAGE_NAME%
    
    REM Get AWS account ID
    echo Getting AWS account ID...
    for /f "tokens=*" %%a in ('aws sts get-caller-identity --query Account --output text') do set AWS_ACCOUNT_ID=%%a
    if "!AWS_ACCOUNT_ID!"=="" (
        echo Error: Failed to get AWS account ID. Ensure AWS CLI is configured.
        exit /b 1
    )
    
    set REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com
    set FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!
    
    echo AWS Account ID: !AWS_ACCOUNT_ID!
    echo ECR Registry: !REGISTRY_URL!
    echo Full Image Name: !FULL_IMAGE_NAME!
    echo.
    
    REM Login to ECR
    echo Logging in to AWS ECR...
    aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
    if !ERRORLEVEL! neq 0 (
        echo Error: ECR login failed
        exit /b 1
    )
    echo Successfully logged in to ECR
    echo.
    
    REM Check if ECR repository exists, create if not
    echo Checking if ECR repository exists...
    aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
    if !ERRORLEVEL! neq 0 (
        echo Repository does not exist. Creating ECR repository: !ECR_REPO!
        aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION! --image-scanning-configuration scanOnPush=true
        if !ERRORLEVEL! neq 0 (
            echo Error: Failed to create ECR repository
            exit /b 1
        )
        echo ECR repository created
    )
    echo.
    
) else if "!REGISTRY_CHOICE!"=="2" (
    echo.
    echo === Docker Hub Configuration ===
    
    REM Prompt for Docker Hub credentials
    set /p DOCKER_USERNAME="Enter Docker Hub username: "
    if "!DOCKER_USERNAME!"=="" (
        echo Error: Docker Hub username is required
        exit /b 1
    )
    
    set /p DOCKER_PASSWORD="Enter Docker Hub password/token: "
    if "!DOCKER_PASSWORD!"=="" (
        echo Error: Docker Hub password is required
        exit /b 1
    )
    
    set FULL_IMAGE_NAME=!DOCKER_USERNAME!/%IMAGE_NAME%:!IMAGE_TAG!
    
    echo Docker Hub Username: !DOCKER_USERNAME!
    echo Full Image Name: !FULL_IMAGE_NAME!
    echo.
    
    REM Login to Docker Hub
    echo Logging in to Docker Hub...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo Error: Docker Hub login failed
        exit /b 1
    )
    echo Successfully logged in to Docker Hub
    echo.
    
) else (
    echo Error: Invalid choice. Please select 1 or 2.
    exit /b 1
)

REM Build Docker image
echo ==========================================
echo Building Docker Image
echo ==========================================
echo Image: !FULL_IMAGE_NAME!
echo.

docker build -t "!FULL_IMAGE_NAME!" .
if !ERRORLEVEL! neq 0 (
    echo Error: Docker build failed
    exit /b 1
)

echo.
echo Docker image built successfully
echo.

REM Push Docker image
echo ==========================================
echo Pushing Docker Image
echo ==========================================
echo Pushing: !FULL_IMAGE_NAME!
echo.

docker push "!FULL_IMAGE_NAME!"
if !ERRORLEVEL! neq 0 (
    echo Error: Docker push failed
    exit /b 1
)

echo.
echo Docker image pushed successfully
echo.

REM Summary
echo ==========================================
echo Build ^& Push Complete
echo ==========================================
echo Image: !FULL_IMAGE_NAME!
echo.
echo Next steps:
echo 1. Update ECS task definition with this image URI
echo 2. Run deploy-image.bat to deploy to AWS ECS Fargate
echo.

endlocal
