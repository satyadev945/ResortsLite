#!/usr/bin/env bash
# =============================================================================
# build-push.sh — Build and push the ResortsLite Docker image
# Supports: AWS ECR  |  Docker Hub
# Usage   : bash scripts/build-push.sh   (run from repository root)
# =============================================================================
set -e
set -o pipefail

PROJECT_NAME="resortsLite"
DOCKERFILE_PATH="Dockerfile"

# ---------------------------------------------------------------------------
# Sanitise image name: lowercase, replace non-alphanumeric with hyphens,
# strip leading/trailing hyphens.
# ---------------------------------------------------------------------------
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "=============================================="
echo "  ResortsLite — Docker Build & Push"
echo "=============================================="
echo ""

# ---------------------------------------------------------------------------
# Prompt for image tag
# ---------------------------------------------------------------------------
read -rp "Enter image tag [latest]: " RAW_TAG
RAW_TAG="${RAW_TAG:-latest}"
IMAGE_TAG=$(echo "$RAW_TAG" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
IMAGE_TAG="${IMAGE_TAG:-latest}"
echo "Image tag: $IMAGE_TAG"
echo ""

# ---------------------------------------------------------------------------
# Registry selection
# ---------------------------------------------------------------------------
echo "Select container registry:"
echo "  1) AWS ECR"
echo "  2) Docker Hub"
read -rp "Enter choice [1]: " REGISTRY_CHOICE
REGISTRY_CHOICE="${REGISTRY_CHOICE:-1}"

# ---------------------------------------------------------------------------
# AWS ECR
# ---------------------------------------------------------------------------
if [ "$REGISTRY_CHOICE" = "1" ]; then
  echo ""
  echo "--- AWS ECR Configuration ---"
  read -rp "AWS Region [us-east-1]: " AWS_REGION
  AWS_REGION="${AWS_REGION:-us-east-1}"

  read -rp "AWS Account ID: " AWS_ACCOUNT_ID
  if [ -z "$AWS_ACCOUNT_ID" ]; then
    echo "Fetching AWS Account ID from STS..."
    AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
  fi

  ECR_REPO="${IMAGE_NAME}"
  REGISTRY_URL="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
  FULL_IMAGE_NAME="${REGISTRY_URL}/${ECR_REPO}:${IMAGE_TAG}"

  echo ""
  echo "Logging in to ECR: $REGISTRY_URL"
  aws ecr get-login-password --region "$AWS_REGION" | \
    docker login --username AWS --password-stdin "$REGISTRY_URL"

  echo "Checking / creating ECR repository: $ECR_REPO"
  aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_REGION" >/dev/null 2>&1 || \
    aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION"

# ---------------------------------------------------------------------------
# Docker Hub
# ---------------------------------------------------------------------------
elif [ "$REGISTRY_CHOICE" = "2" ]; then
  echo ""
  echo "--- Docker Hub Configuration ---"
  read -rp "Docker Hub username: " DOCKER_USERNAME
  read -rsp "Docker Hub password/token: " DOCKER_PASSWORD
  echo ""
  read -rp "Docker Hub namespace [$DOCKER_USERNAME]: " DOCKER_NAMESPACE
  DOCKER_NAMESPACE="${DOCKER_NAMESPACE:-$DOCKER_USERNAME}"

  FULL_IMAGE_NAME="${DOCKER_NAMESPACE}/${IMAGE_NAME}:${IMAGE_TAG}"

  echo "Logging in to Docker Hub..."
  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin

else
  echo "Invalid choice. Exiting."
  exit 1
fi

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
echo ""
echo "Building Docker image: $FULL_IMAGE_NAME"
echo "Build context: . (repository root)"
docker build -f "$DOCKERFILE_PATH" -t "$FULL_IMAGE_NAME" .

echo ""
echo "Build successful: $FULL_IMAGE_NAME"

# ---------------------------------------------------------------------------
# Push
# ---------------------------------------------------------------------------
echo ""
echo "Pushing image to registry..."
docker push "$FULL_IMAGE_NAME"

echo ""
echo "=============================================="
echo "  Image pushed successfully!"
echo "  $FULL_IMAGE_NAME"
echo "=============================================="
