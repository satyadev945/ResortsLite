@echo off
setlocal enabledelayedexpansion

set "PROJECT_NAME=Test_Comp1"
set "MODULE_DOCKERFILE=Dockerfile"

set /p IMAGE_TAG="Enter image tag (default: latest): "
if "!IMAGE_TAG!"=="" set "IMAGE_TAG=latest"
for /f %%A in ('powershell -NoProfile -Command "$v='"'"'!IMAGE_TAG!'"'"'.ToLower(); $v=[regex]::Replace($v,'[^a-z0-9._-]','-'); $v=$v.Trim('-'); if([string]::IsNullOrWhiteSpace($v)){$v='latest'}; Write-Output $v"') do set "IMAGE_TAG=%%A"
for /f %%A in ('powershell -NoProfile -Command "$v='"'"'!PROJECT_NAME!'"'"'.ToLower(); $v=[regex]::Replace($v,'[^a-z0-9]','-'); $v=[regex]::Replace($v,'-+','-'); $v=$v.Trim('-'); Write-Output $v"') do set "IMAGE_NAME=%%A"

echo Select registry type:
echo 1^) Azure ACR
echo 2^) Docker Hub
set /p REGISTRY_CHOICE="Enter choice [1-2]: "

if "!REGISTRY_CHOICE!"=="1" (
  set /p ACR_NAME="Enter Azure ACR name: "
  if "!ACR_NAME!"=="" (
    echo ACR name is required.
    exit /b 1
  )
  az acr login --name !ACR_NAME!
  if !ERRORLEVEL! neq 0 (
    echo ACR login failed
    exit /b 1
  )
  set "FULL_IMAGE_NAME=!ACR_NAME!.azurecr.io/!IMAGE_NAME!:!IMAGE_TAG!"
) else if "!REGISTRY_CHOICE!"=="2" (
  set /p DOCKER_USERNAME="Enter Docker Hub username: "
  set /p DOCKER_REPOSITORY="Enter Docker Hub repository (default: !IMAGE_NAME!): "
  if "!DOCKER_REPOSITORY!"=="" set "DOCKER_REPOSITORY=!IMAGE_NAME!"
  for /f %%A in ('powershell -NoProfile -Command "$v='"'"'!DOCKER_REPOSITORY!'"'"'.ToLower(); $v=[regex]::Replace($v,'[^a-z0-9._-]','-'); $v=$v.Trim('-'); Write-Output $v"') do set "DOCKER_REPOSITORY=%%A"
  set /p DOCKER_PASSWORD="Enter Docker Hub password or access token: "
  echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
  if !ERRORLEVEL! neq 0 (
    echo Docker Hub login failed
    exit /b 1
  )
  set "FULL_IMAGE_NAME=!DOCKER_USERNAME!/!DOCKER_REPOSITORY!:!IMAGE_TAG!"
) else (
  echo Invalid registry choice.
  exit /b 1
)

echo Building image: !FULL_IMAGE_NAME!
docker build -f "%MODULE_DOCKERFILE%" -t !FULL_IMAGE_NAME! .
if !ERRORLEVEL! neq 0 (
  echo Docker build failed
  exit /b 1
)

echo Pushing image: !FULL_IMAGE_NAME!
docker push !FULL_IMAGE_NAME!
if !ERRORLEVEL! neq 0 (
  echo Docker push failed
  exit /b 1
)

echo Image pushed successfully: !FULL_IMAGE_NAME!
endlocal
