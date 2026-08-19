#!/bin/bash
set -e
set -o pipefail

# ============================================================
# build-push.sh — Build and push the ResortsLite Docker image
# ============================================================

PROJECT_NAME="resortslite"
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "=============================================="
echo "  ResortsLite — Docker Build & Push"
echo "=============================================="
echo ""

# ---- Registry selection ----
echo "Select target registry:"
echo "  1. Google Artifact Registry"
echo "  2. Docker Hub"
echo ""
read -rp "Enter choice [1 or 2]: " REGISTRY_CHOICE

# ---- Image tag ----
read -rp "Enter image tag (press Enter for 'latest'): " RAW_TAG
IMAGE_TAG=$(echo "$RAW_TAG" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
if [ -z "$IMAGE_TAG" ]; then
  IMAGE_TAG="latest"
fi

echo ""
echo "Image name : $IMAGE_NAME"
echo "Image tag  : $IMAGE_TAG"
echo ""

# ============================================================
# Google Artifact Registry
# ============================================================
if [ "$REGISTRY_CHOICE" = "1" ]; then
  read -rp "Enter GCP Project ID: " GCP_PROJECT
  read -rp "Enter GCP Region (e.g. us-central1): " GCP_REGION
  read -rp "Enter Artifact Registry repository name: " AR_REPO

  FULL_IMAGE_NAME="${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT}/${AR_REPO}/${IMAGE_NAME}:${IMAGE_TAG}"

  echo ""
  echo "Authenticating with Google Artifact Registry..."
  gcloud auth configure-docker "${GCP_REGION}-docker.pkg.dev" --quiet

  echo "Building Docker image: $FULL_IMAGE_NAME"
  docker build -f Dockerfile -t "$FULL_IMAGE_NAME" .

  echo "Pushing image to Artifact Registry..."
  docker push "$FULL_IMAGE_NAME"

  echo ""
  echo "✅ Image pushed successfully: $FULL_IMAGE_NAME"

# ============================================================
# Docker Hub
# ============================================================
elif [ "$REGISTRY_CHOICE" = "2" ]; then
  read -rp "Enter Docker Hub username: " DOCKER_USERNAME
  read -rsp "Enter Docker Hub password/token: " DOCKER_PASSWORD
  echo ""

  FULL_IMAGE_NAME="${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}"

  echo ""
  echo "Authenticating with Docker Hub..."
  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin

  echo "Building Docker image: $FULL_IMAGE_NAME"
  docker build -f Dockerfile -t "$FULL_IMAGE_NAME" .

  echo "Pushing image to Docker Hub..."
  docker push "$FULL_IMAGE_NAME"

  echo ""
  echo "✅ Image pushed successfully: $FULL_IMAGE_NAME"

else
  echo "❌ Invalid choice. Exiting."
  exit 1
fi
