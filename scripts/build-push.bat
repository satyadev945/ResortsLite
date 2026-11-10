@echo off
setlocal enabledelayedexpansion

set "PROJECT_NAME=ResortsLite"
powershell -NoProfile -Command "$n='%PROJECT_NAME%'.ToLower() -replace '[^a-z0-9]+','-' -replace '^-+|-+$',''; Set-Content -Path '%TEMP%\rl_name.txt' -Value $n -NoNewline"
set /p IMAGE_NAME=<"%TEMP%\rl_name.txt"
del "%TEMP%\rl_name.txt" >nul 2>&1
if "!IMAGE_NAME!"=="" set "IMAGE_NAME=resortslite"

echo === %PROJECT_NAME%: Docker build and push ===

if not exist Dockerfile (
  echo ERROR: Dockerfile not found. Run this script from the repository root.
  exit /b 1
)

set /p IMAGE_TAG="Enter image tag [latest]: "
if "!IMAGE_TAG!"=="" set "IMAGE_TAG=latest"
set "TAG_RAW=!IMAGE_TAG!"
powershell -NoProfile -Command "$t='!TAG_RAW!'.ToLower() -replace '[^a-z0-9._]+','-' -replace '^-+|-+$',''; if ($t -eq '') { $t='latest' }; Set-Content -Path '%TEMP%\rl_tag.txt' -Value $t -NoNewline"
set /p IMAGE_TAG=<"%TEMP%\rl_tag.txt"
del "%TEMP%\rl_tag.txt" >nul 2>&1
if "!IMAGE_TAG!"=="" set "IMAGE_TAG=latest"

echo Select registry type:
echo   1. Azure Container Registry (ACR)
echo   2. Docker Hub
set /p REGISTRY_CHOICE="Choice [1/2]: "

if "!REGISTRY_CHOICE!"=="1" (
  set /p ACR_NAME="Enter ACR name (without .azurecr.io): "
  if "!ACR_NAME!"=="" (
    echo ERROR: ACR name is required
    exit /b 1
  )
  echo Logging in to ACR !ACR_NAME!...
  az acr login --name !ACR_NAME!
  if !ERRORLEVEL! neq 0 (
    echo ACR login failed
    exit /b 1
  )
  set "FULL_IMAGE_NAME=!ACR_NAME!.azurecr.io/!IMAGE_NAME!:!IMAGE_TAG!"
) else if "!REGISTRY_CHOICE!"=="2" (
  set /p DOCKER_USERNAME="Enter Docker Hub username: "
  if "!DOCKER_USERNAME!"=="" (
    echo ERROR: Docker Hub username is required
    exit /b 1
  )
  set /p DOCKER_PASSWORD="Enter Docker Hub password/token: "
  echo !DOCKER_PASSWORD!| docker login --username !DOCKER_USERNAME! --password-stdin
  if !ERRORLEVEL! neq 0 (
    echo Docker Hub login failed
    exit /b 1
  )
  set "FULL_IMAGE_NAME=!DOCKER_USERNAME!/!IMAGE_NAME!:!IMAGE_TAG!"
) else (
  echo ERROR: Invalid choice
  exit /b 1
)

echo Building image !FULL_IMAGE_NAME!...
docker build -f Dockerfile -t !FULL_IMAGE_NAME! .
if !ERRORLEVEL! neq 0 (
  echo Docker build failed
  exit /b 1
)

echo Pushing image !FULL_IMAGE_NAME!...
docker push !FULL_IMAGE_NAME!
if !ERRORLEVEL! neq 0 (
  echo Docker push failed
  exit /b 1
)

echo === Done. Image pushed: !FULL_IMAGE_NAME! ===
echo Use this URI with scripts\deploy-image.bat
endlocal
