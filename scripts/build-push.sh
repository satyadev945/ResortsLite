#!/bin/bash
# Builds the Resortlite_Mono1 image and pushes it to AWS ECR or Docker Hub.
# Run from the repository root: ./scripts/build-push.sh
set -e
set -o pipefail

PROJECT_NAME="Resortlite_Mono1"
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "=== Build and push: $IMAGE_NAME ==="
echo "Select the registry:"
echo "  1. AWS ECR (target platform)"
echo "  2. Docker Hub"
read -r -p "Enter 1 or 2: " REGISTRY_CHOICE

case "$REGISTRY_CHOICE" in
  1)
    command -v aws >/dev/null 2>&1 || { echo "ERROR: the AWS CLI is required"; exit 1; }
    read -r -p "Enter the AWS region (for example us-east-1): " AWS_REGION
    read -r -p "Enter the AWS account id: " AWS_ACCOUNT_ID
    read -r -p "Enter the ECR repository name [$IMAGE_NAME]: " ECR_REPO
    ECR_REPO=$(echo "${ECR_REPO:-$IMAGE_NAME}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')
    REGISTRY_URL="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
    REPO_PATH="$ECR_REPO"
    ;;
  2)
    read -r -p "Enter the Docker Hub username: " DOCKER_USERNAME
    read -r -s -p "Enter the Docker Hub password or access token: " DOCKER_PASSWORD
    echo
    read -r -p "Enter the repository name [$IMAGE_NAME]: " DOCKER_REPO
    DOCKER_REPO=$(echo "${DOCKER_REPO:-$IMAGE_NAME}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')
    REGISTRY_URL="docker.io"
    REPO_PATH="${DOCKER_USERNAME}/${DOCKER_REPO}"
    ;;
  *)
    echo "ERROR: invalid choice, enter 1 or 2"
    exit 1
    ;;
esac

read -r -p "Enter IMAGE_TAG [latest]: " IMAGE_TAG
IMAGE_TAG=$(echo "${IMAGE_TAG:-latest}" | tr -cs 'A-Za-z0-9_.-' '-' | sed 's/^-*//;s/-*$//')
IMAGE_TAG="${IMAGE_TAG:-latest}"
FULL_IMAGE_NAME="${REGISTRY_URL}/${REPO_PATH}:${IMAGE_TAG}"

echo "Building image $FULL_IMAGE_NAME ..."
docker build -f Dockerfile -t "$FULL_IMAGE_NAME" .
echo "Build finished."

if [ "$REGISTRY_CHOICE" = "1" ]; then
  echo "Logging in to AWS ECR $REGISTRY_URL ..."
  aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$REGISTRY_URL"
  echo "Checking the ECR repository $ECR_REPO ..."
  aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_REGION" >/dev/null 2>&1 || aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION"
else
  echo "Logging in to Docker Hub ..."
  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin
fi

echo "Pushing $FULL_IMAGE_NAME ..."
docker push "$FULL_IMAGE_NAME"
echo "Push finished. Image URI: $FULL_IMAGE_NAME"
echo "Use this image URI with scripts/deploy-image.sh"
