#!/bin/bash
set -e

PROJECT_NAME="resortslite"
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "============================================"
echo "  ResortsLite - Docker Build & Push Script"
echo "============================================"
echo ""
echo "Select container registry:"
echo "  1. Azure Container Registry (ACR)"
echo "  2. Docker Hub"
echo ""
read -p "Enter choice [1 or 2]: " REGISTRY_CHOICE

# Prompt for image tag
read -p "Enter image tag (press Enter for 'latest'): " IMAGE_TAG_INPUT
IMAGE_TAG=$(echo "$IMAGE_TAG_INPUT" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
if [ -z "$IMAGE_TAG" ]; then
  IMAGE_TAG="latest"
fi

echo ""

if [ "$REGISTRY_CHOICE" = "1" ]; then
  # Azure Container Registry
  read -p "Enter ACR name (e.g., myregistry): " ACR_NAME
  ACR_NAME=$(echo "$ACR_NAME" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9]//g')
  FULL_IMAGE_NAME="${ACR_NAME}.azurecr.io/${IMAGE_NAME}:${IMAGE_TAG}"

  echo ""
  echo "Logging in to Azure Container Registry: $ACR_NAME ..."
  az acr login --name "$ACR_NAME"
  echo "ACR login successful."

elif [ "$REGISTRY_CHOICE" = "2" ]; then
  # Docker Hub
  read -p "Enter Docker Hub username: " DOCKER_USERNAME
  read -s -p "Enter Docker Hub password: " DOCKER_PASSWORD
  echo ""
  FULL_IMAGE_NAME="${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}"

  echo ""
  echo "Logging in to Docker Hub ..."
  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin
  echo "Docker Hub login successful."

else
  echo "Invalid choice. Exiting."
  exit 1
fi

echo ""
echo "Building Docker image: $FULL_IMAGE_NAME"
echo "Build context: . (repository root)"
docker build -f Dockerfile -t "$FULL_IMAGE_NAME" .
echo "Docker build successful."

echo ""
echo "Pushing image: $FULL_IMAGE_NAME ..."
docker push "$FULL_IMAGE_NAME"
echo "Image pushed successfully."

echo ""
echo "============================================"
echo "  Build & Push Complete"
echo "  Image: $FULL_IMAGE_NAME"
echo "============================================"
