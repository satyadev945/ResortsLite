#!/bin/bash
set -e
set -o pipefail

# Run from repository root: ./scripts/build-push.sh
PROJECT_NAME="resortslite"
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "=== Build and push image: $IMAGE_NAME ==="

read -r -p "Enter image tag [latest]: " IMAGE_TAG
IMAGE_TAG=$(echo "${IMAGE_TAG:-latest}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._' '-' | sed 's/^-*//;s/-*$//')
IMAGE_TAG=${IMAGE_TAG:-latest}

echo "Select registry type:"
echo "  1. Azure Container Registry (ACR)"
echo "  2. Docker Hub"
read -r -p "Choice [1/2]: " REGISTRY_CHOICE

case "$REGISTRY_CHOICE" in
  1)
    read -r -p "Enter ACR name (without .azurecr.io): " ACR_NAME
    if [ -z "$ACR_NAME" ]; then echo "ACR name is required"; exit 1; fi
    ACR_NAME=$(echo "$ACR_NAME" | tr '[:upper:]' '[:lower:]')
    FULL_IMAGE_NAME="${ACR_NAME}.azurecr.io/${IMAGE_NAME}:${IMAGE_TAG}"
    echo "Logging in to ACR..."
    az acr login --name "$ACR_NAME"
    ;;
  2)
    read -r -p "Enter Docker Hub username: " DOCKER_USERNAME
    if [ -z "$DOCKER_USERNAME" ]; then echo "Docker Hub username is required"; exit 1; fi
    read -r -s -p "Enter Docker Hub password/token: " DOCKER_PASSWORD
    echo
    FULL_IMAGE_NAME="$(echo "$DOCKER_USERNAME" | tr '[:upper:]' '[:lower:]')/${IMAGE_NAME}:${IMAGE_TAG}"
    echo "Logging in to Docker Hub..."
    echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin
    ;;
  *)
    echo "Invalid choice"; exit 1 ;;
esac

echo "Building image: $FULL_IMAGE_NAME"
docker build -f Dockerfile -t "$FULL_IMAGE_NAME" .

echo "Pushing image: $FULL_IMAGE_NAME"
docker push "$FULL_IMAGE_NAME"

echo "=== Done ==="
echo "Image pushed: $FULL_IMAGE_NAME"
echo "Use this URI with scripts/deploy-image.sh"
