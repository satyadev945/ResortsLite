#!/bin/bash
set -e
set -o pipefail

# ============================================================
# build-push.sh — Build and push Docker image for ResortsLite
# Usage: ./scripts/build-push.sh
# ============================================================

PROJECT_NAME="resortslite"
DOCKERFILE_PATH="Dockerfile"

echo "=============================================="
echo "  ResortsLite — Docker Build & Push Script"
echo "=============================================="
echo ""

# ---- Sanitize image name (lowercase, hyphens only) ----
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

# ---- Prompt for image tag ----
read -rp "Enter image tag [latest]: " INPUT_TAG
INPUT_TAG=$(echo "${INPUT_TAG:-latest}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
IMAGE_TAG="${INPUT_TAG:-latest}"
echo "Using image tag: $IMAGE_TAG"
echo ""

# ---- Registry selection ----
echo "Select container registry:"
echo "  1. AWS ECR"
echo "  2. Docker Hub"
read -rp "Enter choice [1 or 2]: " REGISTRY_CHOICE
echo ""

if [ "$REGISTRY_CHOICE" = "1" ]; then
  # ---- AWS ECR ----
  read -rp "Enter AWS Region (e.g. us-east-1): " AWS_REGION
  read -rp "Enter AWS Account ID: " AWS_ACCOUNT_ID
  ECR_REPO="$IMAGE_NAME"
  REGISTRY_URL="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
  FULL_IMAGE_NAME="${REGISTRY_URL}/${ECR_REPO}:${IMAGE_TAG}"

  echo "Logging in to AWS ECR..."
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
  REGISTRY_URL="docker.io"
  FULL_IMAGE_NAME="${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}"

  echo "Logging in to Docker Hub..."
  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin

else
  echo "ERROR: Invalid registry choice. Exiting."
  exit 1
fi

echo ""
echo "Building Docker image: $FULL_IMAGE_NAME"
echo "----------------------------------------------"
docker build -f "$DOCKERFILE_PATH" -t "$FULL_IMAGE_NAME" .

echo ""
echo "Pushing image: $FULL_IMAGE_NAME"
echo "----------------------------------------------"
docker push "$FULL_IMAGE_NAME"

echo ""
echo "=============================================="
echo "  Build & Push Complete!"
echo "  Image: $FULL_IMAGE_NAME"
echo "=============================================="
