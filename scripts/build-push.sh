#!/bin/bash

# ============================================================================
# Build and Push Docker Image Script for ResortsLite
# Supports Google Artifact Registry and Docker Hub
# ============================================================================

set -e

echo "============================================"
echo "ResortsLite - Docker Build and Push Script"
echo "============================================"
echo ""

# Project configuration
PROJECT_NAME="resortslite"

# Sanitize project name for Docker tag (lowercase, hyphenate)
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

# Prompt for image tag
read -p "Enter image tag (default: latest): " IMAGE_TAG
IMAGE_TAG=${IMAGE_TAG:-latest}

# Sanitize tag
IMAGE_TAG=$(echo "$IMAGE_TAG" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9.-' '-' | sed 's/^-*//;s/-*$//')

echo ""
echo "Select Docker Registry:"
echo "1. Google Artifact Registry"
echo "2. Docker Hub"
read -p "Enter choice (1 or 2): " REGISTRY_CHOICE

if [ "$REGISTRY_CHOICE" = "1" ]; then
    # Google Artifact Registry
    echo ""
    echo "=== Google Artifact Registry Configuration ==="
    read -p "Enter GCP Project ID: " GCP_PROJECT
    read -p "Enter GCP Region (e.g., us-central1): " GCP_REGION
    read -p "Enter Artifact Registry Repository Name: " AR_REPO
    
    REGISTRY="${GCP_REGION}-docker.pkg.dev"
    FULL_IMAGE_NAME="${REGISTRY}/${GCP_PROJECT}/${AR_REPO}/${IMAGE_NAME}:${IMAGE_TAG}"
    
    echo ""
    echo "Authenticating with Google Cloud..."
    gcloud auth login
    
    echo ""
    echo "Setting GCP project..."
    gcloud config set project "$GCP_PROJECT"
    
    echo ""
    echo "Configuring Docker for Artifact Registry..."
    gcloud auth configure-docker "${GCP_REGION}-docker.pkg.dev"
    
elif [ "$REGISTRY_CHOICE" = "2" ]; then
    # Docker Hub
    echo ""
    echo "=== Docker Hub Configuration ==="
    read -p "Enter Docker Hub username: " DOCKER_USERNAME
    read -sp "Enter Docker Hub password/token: " DOCKER_PASSWORD
    echo ""
    
    FULL_IMAGE_NAME="${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}"
    
    echo ""
    echo "Authenticating with Docker Hub..."
    echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin
    
else
    echo "Invalid choice. Exiting."
    exit 1
fi

echo ""
echo "============================================"
echo "Building Docker image..."
echo "Image: $FULL_IMAGE_NAME"
echo "============================================"

docker build -t "$FULL_IMAGE_NAME" .

if [ $? -ne 0 ]; then
    echo "ERROR: Docker build failed!"
    exit 1
fi

echo ""
echo "============================================"
echo "Pushing Docker image to registry..."
echo "============================================"

docker push "$FULL_IMAGE_NAME"

if [ $? -ne 0 ]; then
    echo "ERROR: Docker push failed!"
    exit 1
fi

echo ""
echo "============================================"
echo "SUCCESS!"
echo "============================================"
echo "Image pushed successfully: $FULL_IMAGE_NAME"
echo ""
echo "Next steps:"
echo "1. Update kubernetes/deployment.yaml with the image URI"
echo "2. Run deploy-image.sh to deploy to GKE"
echo "============================================"
