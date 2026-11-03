#!/bin/bash
set -e
set -o pipefail

PROJECT_NAME="Test_Comp1"
MODULE_DOCKERFILE="Dockerfile"

printf "Enter image tag (default: latest): "
read -r IMAGE_TAG
IMAGE_TAG=$(echo "${IMAGE_TAG:-latest}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
if [ -z "$IMAGE_TAG" ]; then
  IMAGE_TAG="latest"
fi

IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "Select registry type:"
echo "1) Azure ACR"
echo "2) Docker Hub"
printf "Enter choice [1-2]: "
read -r REGISTRY_CHOICE

if [ "$REGISTRY_CHOICE" = "1" ]; then
  printf "Enter Azure ACR name: "
  read -r ACR_NAME
  if [ -z "$ACR_NAME" ]; then
    echo "ACR name is required."
    exit 1
  fi
  az acr login --name "$ACR_NAME"
  FULL_IMAGE_NAME="${ACR_NAME}.azurecr.io/${IMAGE_NAME}:${IMAGE_TAG}"
elif [ "$REGISTRY_CHOICE" = "2" ]; then
  printf "Enter Docker Hub username: "
  read -r DOCKER_USERNAME
  printf "Enter Docker Hub repository (default: %s): " "$IMAGE_NAME"
  read -r DOCKER_REPOSITORY
  DOCKER_REPOSITORY=${DOCKER_REPOSITORY:-$IMAGE_NAME}
  DOCKER_REPOSITORY=$(echo "$DOCKER_REPOSITORY" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
  printf "Enter Docker Hub password or access token: "
  read -rs DOCKER_PASSWORD
  echo
  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin
  FULL_IMAGE_NAME="${DOCKER_USERNAME}/${DOCKER_REPOSITORY}:${IMAGE_TAG}"
else
  echo "Invalid registry choice."
  exit 1
fi

echo "Building image: $FULL_IMAGE_NAME"
docker build -f "$MODULE_DOCKERFILE" -t "$FULL_IMAGE_NAME" .

echo "Pushing image: $FULL_IMAGE_NAME"
docker push "$FULL_IMAGE_NAME"

echo "Image pushed successfully: $FULL_IMAGE_NAME"
