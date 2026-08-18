#!/usr/bin/env bash
# =============================================================================
# build-push.sh — Build and push the ResortsLite Docker image
# Supports: AWS ECR and Docker Hub
# Usage   : bash scripts/build-push.sh   (run from repository root)
# =============================================================================
set -e
set -o pipefail

PROJECT_NAME="resortsLite"
DOCKERFILE_PATH="Dockerfile"
BUILD_CONTEXT="."

# ---------------------------------------------------------------------------
# Sanitise image name: lowercase, replace non-alphanumeric with hyphens,
# strip leading/trailing hyphens
# ---------------------------------------------------------------------------
IMAGE_NAME=$(echo "${PROJECT_NAME}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "=============================================="
echo "  ResortsLite — Docker Build & Push"
echo "=============================================="
echo ""

# ---------------------------------------------------------------------------
# Prompt for image tag
# ---------------------------------------------------------------------------
read -rp "Enter image tag [latest]: " IMAGE_TAG_INPUT
IMAGE_TAG=$(echo "${IMAGE_TAG_INPUT}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
if [ -z "${IMAGE_TAG}" ]; then
  IMAGE_TAG="latest"
fi
echo "Using tag: ${IMAGE_TAG}"
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
# Registry-specific configuration
# ---------------------------------------------------------------------------
if [ "${REGISTRY_CHOICE}" = "1" ]; then
  # ---- AWS ECR ----
  echo ""
  echo "--- AWS ECR Configuration ---"
  read -rp "AWS Region [us-east-1]: " AWS_REGION
  AWS_REGION="${AWS_REGION:-us-east-1}"

  read -rp "ECR Repository name [${IMAGE_NAME}]: " ECR_REPO_INPUT
  ECR_REPO="${ECR_REPO_INPUT:-${IMAGE_NAME}}"

  # Derive account ID and registry URL
  echo "Fetching AWS Account ID..."
  ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
  REGISTRY_URL="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
  FULL_IMAGE_NAME="${REGISTRY_URL}/${ECR_REPO}:${IMAGE_TAG}"

  echo ""
  echo "Logging in to ECR..."
  aws ecr get-login-password --region "${AWS_REGION}" | \
    docker login --username AWS --password-stdin "${REGISTRY_URL}"

  # Auto-create ECR repository if it does not exist
  echo "Checking ECR repository '${ECR_REPO}'..."
  aws ecr describe-repositories --repository-names "${ECR_REPO}" --region "${AWS_REGION}" >/dev/null 2>&1 || \
    aws ecr create-repository --repository-name "${ECR_REPO}" --region "${AWS_REGION}"
  echo "ECR repository ready."

elif [ "${REGISTRY_CHOICE}" = "2" ]; then
  # ---- Docker Hub ----
  echo ""
  echo "--- Docker Hub Configuration ---"
  read -rp "Docker Hub username: " DOCKER_USERNAME
  read -rsp "Docker Hub password/token: " DOCKER_PASSWORD
  echo ""
  read -rp "Docker Hub namespace/org [${DOCKER_USERNAME}]: " DOCKER_NAMESPACE_INPUT
  DOCKER_NAMESPACE="${DOCKER_NAMESPACE_INPUT:-${DOCKER_USERNAME}}"

  FULL_IMAGE_NAME="${DOCKER_NAMESPACE}/${IMAGE_NAME}:${IMAGE_TAG}"

  echo "Logging in to Docker Hub..."
  echo "${DOCKER_PASSWORD}" | docker login --username "${DOCKER_USERNAME}" --password-stdin

else
  echo "ERROR: Invalid registry choice '${REGISTRY_CHOICE}'. Exiting."
  exit 1
fi

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
echo ""
echo "Building Docker image: ${FULL_IMAGE_NAME}"
echo "  Dockerfile : ${DOCKERFILE_PATH}"
echo "  Context    : ${BUILD_CONTEXT}"
echo ""
docker build -f "${DOCKERFILE_PATH}" -t "${FULL_IMAGE_NAME}" "${BUILD_CONTEXT}"
echo "Build successful."

# ---------------------------------------------------------------------------
# Push
# ---------------------------------------------------------------------------
echo ""
echo "Pushing image: ${FULL_IMAGE_NAME}"
docker push "${FULL_IMAGE_NAME}"
echo ""
echo "=============================================="
echo "  Image pushed successfully!"
echo "  ${FULL_IMAGE_NAME}"
echo "=============================================="
