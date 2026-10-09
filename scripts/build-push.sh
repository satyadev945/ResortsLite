#!/bin/bash
set -e
set -o pipefail

# ============================================================
# build-push.sh — Build and push Docker image for
# Gitpul Resort MonoCMP (Spring Boot / Java 8)
# ============================================================

PROJECT_NAME="gitpul-resort-monocmp"
DOCKERFILE_PATH="Dockerfile"

echo "=============================================="
echo "  Gitpul Resort MonoCMP — Build & Push"
echo "=============================================="

# ---- Sanitize image name (lowercase, hyphens only) --------
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

# ---- Prompt for image tag ---------------------------------
read -rp "Enter image tag [latest]: " IMAGE_TAG_INPUT
IMAGE_TAG=$(echo "${IMAGE_TAG_INPUT:-latest}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
IMAGE_TAG="${IMAGE_TAG:-latest}"
echo "Using image tag: $IMAGE_TAG"

# ---- Registry selection -----------------------------------
echo ""
echo "Select container registry:"
echo "  1) AWS ECR"
echo "  2) Docker Hub"
read -rp "Enter choice [1]: " REGISTRY_CHOICE
REGISTRY_CHOICE="${REGISTRY_CHOICE:-1}"

if [ "$REGISTRY_CHOICE" = "1" ]; then
  # ---- AWS ECR -------------------------------------------
  echo ""
  read -rp "Enter AWS Region (e.g. us-east-1): " AWS_REGION
  read -rp "Enter AWS Account ID: " AWS_ACCOUNT_ID
  read -rp "Enter ECR repository name [$IMAGE_NAME]: " ECR_REPO_INPUT
  ECR_REPO="${ECR_REPO_INPUT:-$IMAGE_NAME}"

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
  # ---- Docker Hub ----------------------------------------
  echo ""
  read -rp "Enter Docker Hub username: " DOCKER_USERNAME
  read -rsp "Enter Docker Hub password/token: " DOCKER_PASSWORD
  echo ""
  read -rp "Enter Docker Hub repository name [$IMAGE_NAME]: " DOCKER_REPO_INPUT
  DOCKER_REPO="${DOCKER_REPO_INPUT:-$IMAGE_NAME}"

  FULL_IMAGE_NAME="${DOCKER_USERNAME}/${DOCKER_REPO}:${IMAGE_TAG}"

  echo "Authenticating with Docker Hub..."
  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin

else
  echo "Invalid choice. Exiting."
  exit 1
fi

# ---- Build Docker image -----------------------------------
echo ""
echo "Building Docker image: $FULL_IMAGE_NAME"
docker build -f "$DOCKERFILE_PATH" -t "$FULL_IMAGE_NAME" .
echo "Docker build succeeded."

# ---- Push Docker image ------------------------------------
echo ""
echo "Pushing image: $FULL_IMAGE_NAME"
docker push "$FULL_IMAGE_NAME"
echo "Docker push succeeded."

echo ""
echo "=============================================="
echo "  Image pushed successfully!"
echo "  $FULL_IMAGE_NAME"
echo "=============================================="
