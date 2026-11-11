#!/bin/bash
set -e
set -o pipefail

PROJECT_NAME="resortslite"
IMAGE_NAME=$(printf '%s' "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "=== ResortsLite - Build and Push ==="
echo "Run this script from the module root (directory containing Dockerfile)."

read -r -p "Enter image tag [latest]: " IMAGE_TAG
IMAGE_TAG=$(printf '%s' "$IMAGE_TAG" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
IMAGE_TAG=${IMAGE_TAG:-latest}

echo "Select registry:"
echo "  1. AWS ECR"
echo "  2. Docker Hub"
read -r -p "Choice [1/2]: " REGISTRY_CHOICE

case "$REGISTRY_CHOICE" in
  1)
    read -r -p "AWS region (e.g. us-east-1): " AWS_REGION
    [ -z "$AWS_REGION" ] && { echo "AWS region is required"; exit 1; }
    DEFAULT_ACCOUNT=$(aws sts get-caller-identity --query Account --output text 2>/dev/null || true)
    read -r -p "AWS account ID [${DEFAULT_ACCOUNT}]: " AWS_ACCOUNT_ID
    AWS_ACCOUNT_ID=${AWS_ACCOUNT_ID:-$DEFAULT_ACCOUNT}
    [ -z "$AWS_ACCOUNT_ID" ] && { echo "AWS account ID is required"; exit 1; }
    read -r -p "ECR repository name [${IMAGE_NAME}]: " ECR_REPO
    ECR_REPO=${ECR_REPO:-$IMAGE_NAME}
    REGISTRY_URL="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
    FULL_IMAGE_NAME="${REGISTRY_URL}/${ECR_REPO}:${IMAGE_TAG}"

    echo "Logging in to ECR..."
    aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$REGISTRY_URL"

    echo "Ensuring ECR repository exists..."
    aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_REGION" >/dev/null 2>&1 || \
      aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION"
    ;;
  2)
    read -r -p "Docker Hub username: " DOCKER_USERNAME
    [ -z "$DOCKER_USERNAME" ] && { echo "Docker Hub username is required"; exit 1; }
    read -r -s -p "Docker Hub password / access token: " DOCKER_PASSWORD
    echo
    read -r -p "Repository name [${IMAGE_NAME}]: " DOCKER_REPO
    DOCKER_REPO=${DOCKER_REPO:-$IMAGE_NAME}
    DOCKER_USERNAME_LC=$(printf '%s' "$DOCKER_USERNAME" | tr '[:upper:]' '[:lower:]')
    FULL_IMAGE_NAME="${DOCKER_USERNAME_LC}/${DOCKER_REPO}:${IMAGE_TAG}"

    echo "Logging in to Docker Hub..."
    echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin
    ;;
  *)
    echo "Invalid choice"; exit 1 ;;
esac

echo "Building image ${FULL_IMAGE_NAME} ..."
docker build -f Dockerfile -t "$FULL_IMAGE_NAME" .

echo "Pushing image ${FULL_IMAGE_NAME} ..."
docker push "$FULL_IMAGE_NAME"

echo "Done. Image pushed: ${FULL_IMAGE_NAME}"
echo "Use this URI with scripts/deploy-image.sh"
