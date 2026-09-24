#!/bin/bash
# =============================================================================
# build-push.sh — Build and push the ResortsLite Docker image
# Usage: ./scripts/build-push.sh
# Run from the repository root (project root is the Docker build context)
# =============================================================================
set -e
set -o pipefail

PROJECT_NAME="resortslite"
DOCKERFILE_PATH="Dockerfile"

echo "=============================================="
echo "  ResortsLite — Docker Build & Push"
echo "=============================================="

# --------------------------------------------------------------------------
# Sanitize image name: lowercase, replace non-alphanumeric with hyphens,
# strip leading/trailing hyphens
# --------------------------------------------------------------------------
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

# --------------------------------------------------------------------------
# Prompt for image tag
# --------------------------------------------------------------------------
read -rp "Enter image tag [latest]: " IMAGE_TAG_INPUT
IMAGE_TAG=$(echo "${IMAGE_TAG_INPUT:-latest}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
if [ -z "$IMAGE_TAG" ]; then
  IMAGE_TAG="latest"
fi
echo "Image tag: $IMAGE_TAG"

# --------------------------------------------------------------------------
# Registry selection
# --------------------------------------------------------------------------
echo ""
echo "Select container registry:"
echo "  1. AWS ECR"
echo "  2. Docker Hub"
read -rp "Enter choice [1]: " REGISTRY_CHOICE
REGISTRY_CHOICE="${REGISTRY_CHOICE:-1}"

if [ "$REGISTRY_CHOICE" = "1" ]; then
  # ---- AWS ECR ----
  read -rp "Enter AWS region [us-east-1]: " AWS_REGION
  AWS_REGION="${AWS_REGION:-us-east-1}"

  ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
  echo "AWS Account ID: $ACCOUNT_ID"

  ECR_REPO="$IMAGE_NAME"
  REGISTRY_URL="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
  FULL_IMAGE_NAME="${REGISTRY_URL}/${ECR_REPO}:${IMAGE_TAG}"

  echo ""
  echo "Authenticating with ECR..."
  aws ecr get-login-password --region "$AWS_REGION" | \
    docker login --username AWS --password-stdin "$REGISTRY_URL"

  echo "Ensuring ECR repository exists..."
  aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_REGION" >/dev/null 2>&1 || \
    aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION"

elif [ "$REGISTRY_CHOICE" = "2" ]; then
  # ---- Docker Hub ----
  read -rp "Enter Docker Hub username: " DOCKER_USERNAME
  read -rsp "Enter Docker Hub password/token: " DOCKER_PASSWORD
  echo ""
  read -rp "Enter Docker Hub repository name [$IMAGE_NAME]: " DOCKER_REPO
  DOCKER_REPO="${DOCKER_REPO:-$IMAGE_NAME}"

  FULL_IMAGE_NAME="${DOCKER_USERNAME}/${DOCKER_REPO}:${IMAGE_TAG}"

  echo ""
  echo "Authenticating with Docker Hub..."
  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin

else
  echo "Invalid choice. Exiting."
  exit 1
fi

# --------------------------------------------------------------------------
# Build Docker image (build context is repository root)
# --------------------------------------------------------------------------
echo ""
echo "Building Docker image: $FULL_IMAGE_NAME"
docker build -f "$DOCKERFILE_PATH" -t "$FULL_IMAGE_NAME" .

echo ""
echo "Pushing image: $FULL_IMAGE_NAME"
docker push "$FULL_IMAGE_NAME"

echo ""
echo "=============================================="
echo "  Build & Push Complete!"
echo "  Image: $FULL_IMAGE_NAME"
echo "=============================================="
