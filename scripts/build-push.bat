@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: build-push.bat — Build and push the ResortsLite Docker image
:: ============================================================

set "PROJECT_NAME=resortslite"
set "DOCKERFILE_PATH=Dockerfile"
set "BUILD_CONTEXT=."

echo ==============================================
echo   ResortsLite — Docker Build ^& Push Script
echo ==============================================
echo.

:: ---- Sanitise image name (PowerShell) --------------------
for /f "delims=" %%I in ('powershell -NoProfile -Command "$n = 'resortslite'; $n = $n.ToLower() -replace '[^a-z0-9]+','-'; $n = $n.Trim('-'); if ($n -eq '') { $n = 'app' }; $n"') do set "IMAGE_NAME=%%I"

:: ---- Prompt for image tag --------------------------------
set /p "RAW_TAG=Enter image tag [latest]: "
if "!RAW_TAG!"=="" set "RAW_TAG=latest"
for /f "delims=" %%T in ('powershell -NoProfile -Command "$t = '!RAW_TAG!'; $t = $t.ToLower() -replace '[^a-z0-9._-]+','-'; $t = $t.Trim('-'); if ($t -eq '') { $t = 'latest' }; $t"') do set "IMAGE_TAG=%%T"

echo.
echo Select container registry:
echo   1) AWS ECR
echo   2) Docker Hub
set /p "REGISTRY_CHOICE=Enter choice [1]: "
if "!REGISTRY_CHOICE!"=="" set "REGISTRY_CHOICE=1"

:: ============================================================
:: AWS ECR
:: ============================================================
if "!REGISTRY_CHOICE!"=="1" (
  set /p "AWS_REGION=Enter AWS Region [us-east-1]: "
  if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

  set /p "AWS_ACCOUNT_ID=Enter AWS Account ID: "
  if "!AWS_ACCOUNT_ID!"=="" (
    echo ERROR: AWS Account ID is required.
    exit /b 1
  )

  set /p "ECR_REPO=Enter ECR repository name [!IMAGE_NAME!]: "
  if "!ECR_REPO!"=="" set "ECR_REPO=!IMAGE_NAME!"

  set "REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
  set "FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!"

  echo.
  echo Authenticating with AWS ECR...
  aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
  if !ERRORLEVEL! neq 0 (
    echo ERROR: ECR login failed.
    exit /b 1
  )

  echo Ensuring ECR repository exists...
  aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
  if !ERRORLEVEL! neq 0 (
    echo Creating ECR repository...
    aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
      echo ERROR: Failed to create ECR repository.
      exit /b 1
    )
  )

  goto BUILD
)

:: ============================================================
:: Docker Hub
:: ============================================================
if "!REGISTRY_CHOICE!"=="2" (
  set /p "DOCKER_USERNAME=Enter Docker Hub username: "
  if "!DOCKER_USERNAME!"=="" (
    echo ERROR: Docker Hub username is required.
    exit /b 1
  )

  set /p "DOCKER_PASSWORD=Enter Docker Hub password/token: "
  if "!DOCKER_PASSWORD!"=="" (
    echo ERROR: Docker Hub password is required.
    exit /b 1
  )

  set /p "DH_REPO=Enter Docker Hub repository name [!IMAGE_NAME!]: "
  if "!DH_REPO!"=="" set "DH_REPO=!IMAGE_NAME!"

  set "FULL_IMAGE_NAME=!DOCKER_USERNAME!/!DH_REPO!:!IMAGE_TAG!"

  echo.
  echo Authenticating with Docker Hub...
  echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
  if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker Hub login failed.
    exit /b 1
  )

  goto BUILD
)

echo ERROR: Invalid registry choice '!REGISTRY_CHOICE!'.
exit /b 1

:: ============================================================
:: Build
:: ============================================================
:BUILD
echo.
echo Building Docker image: !FULL_IMAGE_NAME!
docker build -f !DOCKERFILE_PATH! -t !FULL_IMAGE_NAME! !BUILD_CONTEXT!
if !ERRORLEVEL! neq 0 (
  echo ERROR: Docker build failed.
  exit /b 1
)

:: ============================================================
:: Push
:: ============================================================
echo.
echo Pushing image: !FULL_IMAGE_NAME!
docker push !FULL_IMAGE_NAME!
if !ERRORLEVEL! neq 0 (
  echo ERROR: Docker push failed.
  exit /b 1
)

echo.
echo ==============================================
echo   SUCCESS: Image pushed successfully!
echo   Image: !FULL_IMAGE_NAME!
echo ==============================================

endlocal
