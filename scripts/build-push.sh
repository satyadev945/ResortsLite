#!/bin/bash
# =============================================================================
# build-push.sh  –  Build and push the ResortsLite Docker image
# Usage: bash scripts/build-push.sh
# Run from the repository root directory.
# =============================================================================
set -e
set -o pipefail

PROJECT_NAME="resortslite"

echo "=============================================="
echo "  ResortsLite – Docker Build & Push"
echo "=============================================="

# ---------- Registry selection ----------
echo ""
echo "Select container registry:"
echo "  1) Google Artifact Registry"
echo "  2) Docker Hub"
read -rp "Enter choice [1 or 2]: " REGISTRY_CHOICE

# ---------- Image tag ----------
read -rp "Enter image tag (leave blank for 'latest'): " RAW_TAG
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')
IMAGE_TAG=$(echo "$RAW_TAG"       | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9.\-' '-' | sed 's/^-*//;s/-*$//')
if [ -z "$IMAGE_TAG" ]; then
  IMAGE_TAG="latest"
fi

# ---------- Registry-specific setup ----------
if [ "$REGISTRY_CHOICE" = "1" ]; then
  echo ""
  echo "--- Google Artifact Registry ---"
  read -rp "GCP Project ID: " GCP_PROJECT
  read -rp "GCP Region (e.g. us-central1): " GCP_REGION
  read -rp "Artifact Registry repository name: " AR_REPO

  echo ""
  echo "Authenticating with Google Cloud..."
  gcloud auth login --quiet
  gcloud config set project "$GCP_PROJECT"
  gcloud auth configure-docker "${GCP_REGION}-docker.pkg.dev" --quiet

  FULL_IMAGE_NAME="${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT}/${AR_REPO}/${IMAGE_NAME}:${IMAGE_TAG}"

elif [ "$REGISTRY_CHOICE" = "2" ]; then
  echo ""
  echo "--- Docker Hub ---"
  read -rp "Docker Hub username: " DOCKER_USERNAME
  read -rsp "Docker Hub password/token: " DOCKER_PASSWORD
  echo ""

  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin

  FULL_IMAGE_NAME="${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}"

else
  echo "Invalid choice. Exiting."
  exit 1
fi

# ---------- Build ----------
echo ""
echo "Building Docker image: $FULL_IMAGE_NAME"
docker build -f Dockerfile -t "$FULL_IMAGE_NAME" .

echo ""
echo "Pushing image: $FULL_IMAGE_NAME"
docker push "$FULL_IMAGE_NAME"

echo ""
echo "=============================================="
echo "  Image pushed successfully!"
echo "  $FULL_IMAGE_NAME"
echo "=============================================="
