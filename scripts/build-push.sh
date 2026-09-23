#!/bin/bash
set -e

# =============================================================
# build-push.sh — Build and push the ResortsLite Docker image
# =============================================================

PROJECT_NAME="resortslite"
DOCKERFILE_PATH="Dockerfile"

echo "=============================================="
echo "  ResortsLite — Docker Build & Push"
echo "=============================================="

# Sanitize project name to a valid Docker image name
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

# Prompt for image tag
read -rp "Enter image tag [latest]: " IMAGE_TAG_INPUT
IMAGE_TAG=$(echo "${IMAGE_TAG_INPUT:-latest}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
IMAGE_TAG="${IMAGE_TAG:-latest}"

echo ""
echo "Select container registry:"
echo "  1) AWS ECR"
echo "  2) Docker Hub"
read -rp "Enter choice [1]: " REGISTRY_CHOICE
REGISTRY_CHOICE="${REGISTRY_CHOICE:-1}"

if [ "$REGISTRY_CHOICE" = "1" ]; then
  # ---- AWS ECR ----
  read -rp "Enter AWS region [us-east-1]: " AWS_REGION
  AWS_REGION="${AWS_REGION:-us-east-1}"

  read -rp "Enter AWS account ID: " AWS_ACCOUNT_ID
  if [ -z "$AWS_ACCOUNT_ID" ]; then
    echo "ERROR: AWS account ID is required." >&2
    exit 1
  fi

  ECR_REPO="${IMAGE_NAME}"
  REGISTRY_URL="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
  FULL_IMAGE_NAME="${REGISTRY_URL}/${ECR_REPO}:${IMAGE_TAG}"

  echo ""
  echo "Authenticating with AWS ECR..."
  aws ecr get-login-password --region "$AWS_REGION" | \
    docker login --username AWS --password-stdin "$REGISTRY_URL"

  echo "Ensuring ECR repository exists..."
  aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_REGION" >/dev/null 2>&1 || \
    aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION"

elif [ "$REGISTRY_CHOICE" = "2" ]; then
  # ---- Docker Hub ----
  read -rp "Enter Docker Hub username: " DOCKER_USERNAME
  if [ -z "$DOCKER_USERNAME" ]; then
    echo "ERROR: Docker Hub username is required." >&2
    exit 1
  fi

  read -rsp "Enter Docker Hub password/token: " DOCKER_PASSWORD
  echo ""
  if [ -z "$DOCKER_PASSWORD" ]; then
    echo "ERROR: Docker Hub password is required." >&2
    exit 1
  fi

  FULL_IMAGE_NAME="${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}"

  echo ""
  echo "Authenticating with Docker Hub..."
  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin

else
  echo "ERROR: Invalid registry choice." >&2
  exit 1
fi

echo ""
echo "Building Docker image: ${FULL_IMAGE_NAME}"
docker build -f "${DOCKERFILE_PATH}" -t "${FULL_IMAGE_NAME}" .

echo ""
echo "Pushing Docker image: ${FULL_IMAGE_NAME}"
docker push "${FULL_IMAGE_NAME}"

echo ""
echo "=============================================="
echo "  Build & Push Complete!"
echo "  Image: ${FULL_IMAGE_NAME}"
echo "=============================================="
