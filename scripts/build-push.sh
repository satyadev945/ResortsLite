#!/bin/bash
set -e
set -o pipefail

# =============================================================
# build-push.sh — Build and push orcappdbmmono Docker image
# =============================================================

PROJECT_NAME="orcappdbmmono"

# Sanitize image name: lowercase, replace non-alphanumeric with hyphens, trim hyphens
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "=============================================="
echo "  Build & Push: $PROJECT_NAME"
echo "=============================================="
echo ""

# --- Registry selection ---
echo "Select container registry:"
echo "  1) Azure Container Registry (ACR)"
echo "  2) Docker Hub"
read -rp "Enter choice [1 or 2]: " REGISTRY_CHOICE

if [ "$REGISTRY_CHOICE" = "1" ]; then
    read -rp "Enter ACR name (e.g. myregistry): " ACR_NAME
    ACR_NAME=$(echo "$ACR_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')
    REGISTRY="${ACR_NAME}.azurecr.io"
    echo "Logging in to ACR: $REGISTRY"
    az acr login --name "$ACR_NAME"
elif [ "$REGISTRY_CHOICE" = "2" ]; then
    read -rp "Enter Docker Hub username: " DOCKER_USERNAME
    read -rsp "Enter Docker Hub password/token: " DOCKER_PASSWORD
    echo ""
    echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin
    REGISTRY="$DOCKER_USERNAME"
else
    echo "ERROR: Invalid choice. Exiting."
    exit 1
fi

# --- Image tag ---
read -rp "Enter image tag [default: latest]: " IMAGE_TAG
IMAGE_TAG=$(echo "$IMAGE_TAG" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
if [ -z "$IMAGE_TAG" ]; then
    IMAGE_TAG="latest"
fi

FULL_IMAGE_NAME="${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"

echo ""
echo "Building image: $FULL_IMAGE_NAME"
echo "----------------------------------------------"

# Build from repository root (Dockerfile is in project root)
docker build -f Dockerfile -t "$FULL_IMAGE_NAME" .

if [ $? -ne 0 ]; then
    echo "ERROR: Docker build failed."
    exit 1
fi

echo ""
echo "Pushing image: $FULL_IMAGE_NAME"
echo "----------------------------------------------"
docker push "$FULL_IMAGE_NAME"

if [ $? -ne 0 ]; then
    echo "ERROR: Docker push failed."
    exit 1
fi

echo ""
echo "=============================================="
echo "  SUCCESS: Image pushed successfully"
echo "  Image: $FULL_IMAGE_NAME"
echo "=============================================="
