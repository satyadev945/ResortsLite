@echo off
setlocal enabledelayedexpansion

set "PROJECT_NAME=ResortsLite"
for /f %%i in ('powershell -NoProfile -Command "$n='%PROJECT_NAME%'.ToLower(); $n=[regex]::Replace($n,'[^a-z0-9]+','-'); $n=$n.Trim('-'); if([string]::IsNullOrWhiteSpace($n)){$n='app'}; Write-Output $n"') do set "IMAGE_NAME=%%i"

set /p IMAGE_TAG="Enter image tag [latest]: "
if "!IMAGE_TAG!"=="" set "IMAGE_TAG=latest"
for /f %%i in ('powershell -NoProfile -Command "$t='%IMAGE_TAG%'.ToLower(); $t=[regex]::Replace($t,'[^a-z0-9._-]+','-'); $t=$t.Trim('-'); if([string]::IsNullOrWhiteSpace($t)){$t='latest'}; Write-Output $t"') do set "IMAGE_TAG=%%i"

echo Select registry type:
echo 1^) Azure ACR
echo 2^) Docker Hub
set /p REGISTRY_CHOICE="Enter choice [1-2]: "

if "!REGISTRY_CHOICE!"=="1" (
  set /p ACR_NAME="Enter Azure ACR name: "
  if "!ACR_NAME!"=="" (
    echo ACR name is required
    exit /b 1
  )
  echo Logging into Azure ACR...
  az acr login --name !ACR_NAME!
  if !ERRORLEVEL! neq 0 (
    echo ACR login failed
    exit /b 1
  )
  set "FULL_IMAGE_NAME=!ACR_NAME!.azurecr.io/!IMAGE_NAME!:!IMAGE_TAG!"
) else if "!REGISTRY_CHOICE!"=="2" (
  set /p DOCKER_USERNAME="Enter Docker Hub username: "
  set /p DOCKER_PASSWORD="Enter Docker Hub password: "
  if "!DOCKER_USERNAME!"=="" (
    echo Docker Hub username is required
    exit /b 1
  )
  if "!DOCKER_PASSWORD!"=="" (
    echo Docker Hub password is required
    exit /b 1
  )
  echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
  if !ERRORLEVEL! neq 0 (
    echo Docker login failed
    exit /b 1
  )
  set "FULL_IMAGE_NAME=!DOCKER_USERNAME!/!IMAGE_NAME!:!IMAGE_TAG!"
) else (
  echo Invalid registry selection
  exit /b 1
)

echo Building image !FULL_IMAGE_NAME! ...
docker build -f Dockerfile -t !FULL_IMAGE_NAME! .
if !ERRORLEVEL! neq 0 (
  echo Docker build failed
  exit /b 1
)

echo Pushing image !FULL_IMAGE_NAME! ...
docker push !FULL_IMAGE_NAME!
if !ERRORLEVEL! neq 0 (
  echo Docker push failed
  exit /b 1
)

echo Image pushed successfully: !FULL_IMAGE_NAME!
endlocal
