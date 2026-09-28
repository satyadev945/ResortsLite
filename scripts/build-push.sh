#!/bin/bash

################################################################################
# Build and Push Script for ResortsLite Docker Image
# Supports AWS ECR and Docker Hub registries
# Usage: ./build-push.sh
################################################################################

set -e  # Exit on error
set -o pipefail  # Exit on pipe failure

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=========================================="
echo "ResortsLite - Docker Build & Push Script"
echo "=========================================="
echo ""

# Project configuration
PROJECT_NAME="resortslite"

# Sanitize project name for Docker tag (lowercase, hyphenate special chars)
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo -e "${GREEN}Project:${NC} $PROJECT_NAME"
echo -e "${GREEN}Sanitized Image Name:${NC} $IMAGE_NAME"
echo ""

# Prompt for image tag
read -p "Enter image tag (default: latest): " IMAGE_TAG
IMAGE_TAG=${IMAGE_TAG:-latest}

# Sanitize tag
IMAGE_TAG=$(echo "$IMAGE_TAG" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9.-' '-' | sed 's/^-*//;s/-*$//')
echo -e "${GREEN}Image Tag:${NC} $IMAGE_TAG"
echo ""

# Registry selection
echo "Select container registry:"
echo "1. AWS ECR (Elastic Container Registry)"
echo "2. Docker Hub"
read -p "Enter choice (1 or 2): " REGISTRY_CHOICE

if [ "$REGISTRY_CHOICE" == "1" ]; then
    echo ""
    echo "=== AWS ECR Configuration ==="
    
    # Prompt for AWS region
    read -p "Enter AWS region (e.g., us-east-1): " AWS_REGION
    if [ -z "$AWS_REGION" ]; then
        echo -e "${RED}Error: AWS region is required${NC}"
        exit 1
    fi
    
    # Prompt for ECR repository name
    read -p "Enter ECR repository name (default: $IMAGE_NAME): " ECR_REPO
    ECR_REPO=${ECR_REPO:-$IMAGE_NAME}
    
    # Get AWS account ID
    echo -e "${YELLOW}Getting AWS account ID...${NC}"
    AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
    if [ -z "$AWS_ACCOUNT_ID" ]; then
        echo -e "${RED}Error: Failed to get AWS account ID. Ensure AWS CLI is configured.${NC}"
        exit 1
    fi
    
    REGISTRY_URL="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
    FULL_IMAGE_NAME="${REGISTRY_URL}/${ECR_REPO}:${IMAGE_TAG}"
    
    echo -e "${GREEN}AWS Account ID:${NC} $AWS_ACCOUNT_ID"
    echo -e "${GREEN}ECR Registry:${NC} $REGISTRY_URL"
    echo -e "${GREEN}Full Image Name:${NC} $FULL_IMAGE_NAME"
    echo ""
    
    # Login to ECR
    echo -e "${YELLOW}Logging in to AWS ECR...${NC}"
    aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$REGISTRY_URL"
    
    if [ $? -ne 0 ]; then
        echo -e "${RED}Error: ECR login failed${NC}"
        exit 1
    fi
    echo -e "${GREEN}✓ Successfully logged in to ECR${NC}"
    echo ""
    
    # Check if ECR repository exists, create if not
    echo -e "${YELLOW}Checking if ECR repository exists...${NC}"
    aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_REGION" >/dev/null 2>&1 || {
        echo -e "${YELLOW}Repository does not exist. Creating ECR repository: $ECR_REPO${NC}"
        aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION" --image-scanning-configuration scanOnPush=true
        echo -e "${GREEN}✓ ECR repository created${NC}"
    }
    echo ""
    
elif [ "$REGISTRY_CHOICE" == "2" ]; then
    echo ""
    echo "=== Docker Hub Configuration ==="
    
    # Prompt for Docker Hub credentials
    read -p "Enter Docker Hub username: " DOCKER_USERNAME
    if [ -z "$DOCKER_USERNAME" ]; then
        echo -e "${RED}Error: Docker Hub username is required${NC}"
        exit 1
    fi
    
    read -sp "Enter Docker Hub password/token: " DOCKER_PASSWORD
    echo ""
    if [ -z "$DOCKER_PASSWORD" ]; then
        echo -e "${RED}Error: Docker Hub password is required${NC}"
        exit 1
    fi
    
    FULL_IMAGE_NAME="${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}"
    
    echo -e "${GREEN}Docker Hub Username:${NC} $DOCKER_USERNAME"
    echo -e "${GREEN}Full Image Name:${NC} $FULL_IMAGE_NAME"
    echo ""
    
    # Login to Docker Hub
    echo -e "${YELLOW}Logging in to Docker Hub...${NC}"
    echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin
    
    if [ $? -ne 0 ]; then
        echo -e "${RED}Error: Docker Hub login failed${NC}"
        exit 1
    fi
    echo -e "${GREEN}✓ Successfully logged in to Docker Hub${NC}"
    echo ""
    
else
    echo -e "${RED}Error: Invalid choice. Please select 1 or 2.${NC}"
    exit 1
fi

# Build Docker image
echo "=========================================="
echo "Building Docker Image"
echo "=========================================="
echo -e "${GREEN}Image:${NC} $FULL_IMAGE_NAME"
echo ""

docker build -t "$FULL_IMAGE_NAME" .

if [ $? -ne 0 ]; then
    echo -e "${RED}Error: Docker build failed${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}✓ Docker image built successfully${NC}"
echo ""

# Push Docker image
echo "=========================================="
echo "Pushing Docker Image"
echo "=========================================="
echo -e "${GREEN}Pushing:${NC} $FULL_IMAGE_NAME"
echo ""

docker push "$FULL_IMAGE_NAME"

if [ $? -ne 0 ]; then
    echo -e "${RED}Error: Docker push failed${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}✓ Docker image pushed successfully${NC}"
echo ""

# Summary
echo "=========================================="
echo "Build & Push Complete"
echo "=========================================="
echo -e "${GREEN}Image:${NC} $FULL_IMAGE_NAME"
echo ""
echo "Next steps:"
echo "1. Update ECS task definition with this image URI"
echo "2. Run deploy-image.sh to deploy to AWS ECS Fargate"
echo ""
