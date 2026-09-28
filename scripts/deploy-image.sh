#!/bin/bash
set -e
set -o pipefail

# ============================================
# Deploy to AWS EKS Script
# For ResortsLite Spring Boot Application
# ============================================

echo "=========================================="
echo "AWS EKS Deployment Script"
echo "=========================================="
echo ""

# Prompt for AWS configuration
read -p "Enter AWS Region (e.g., us-east-1): " AWS_REGION
read -p "Enter EKS Cluster Name: " CLUSTER_NAME

if [ -z "$AWS_REGION" ] || [ -z "$CLUSTER_NAME" ]; then
    echo "ERROR: AWS Region and EKS Cluster Name are required"
    exit 1
fi

# Prompt for Docker image URI
echo ""
read -p "Enter Docker Image URI (e.g., 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI

if [ -z "$IMAGE_URI" ]; then
    echo "ERROR: Docker Image URI is required"
    exit 1
fi

# Prompt for environment-specific configuration
echo ""
echo "=== Application Configuration ==="
echo "Enter values for environment variables (or press Enter to use defaults)"
echo ""

read -p "Enter SPRING_DATASOURCE_URL (default: jdbc:h2:mem:resortdb): " SPRING_DATASOURCE_URL
SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL:-jdbc:h2:mem:resortdb}

read -p "Enter SPRING_DATASOURCE_USERNAME (default: sa): " SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_USERNAME=${SPRING_DATASOURCE_USERNAME:-sa}

read -sp "Enter SPRING_DATASOURCE_PASSWORD (default: empty): " SPRING_DATASOURCE_PASSWORD
echo ""
SPRING_DATASOURCE_PASSWORD=${SPRING_DATASOURCE_PASSWORD:-}

read -p "Enter REDIS_HOST (default: localhost): " REDIS_HOST
REDIS_HOST=${REDIS_HOST:-localhost}

read -p "Enter REDIS_PORT (default: 6379): " REDIS_PORT
REDIS_PORT=${REDIS_PORT:-6379}

read -sp "Enter REDIS_PASSWORD (default: empty): " REDIS_PASSWORD
echo ""
REDIS_PASSWORD=${REDIS_PASSWORD:-}

read -p "Enter PAYMENT_API_URL (default: http://payment-service:9090/payments/charge): " PAYMENT_API_URL
PAYMENT_API_URL=${PAYMENT_API_URL:-http://payment-service:9090/payments/charge}

read -p "Enter APP_PAYMENT_ENDPOINT (default: http://payment-svc.internal:9090/charge): " APP_PAYMENT_ENDPOINT
APP_PAYMENT_ENDPOINT=${APP_PAYMENT_ENDPOINT:-http://payment-svc.internal:9090/charge}

read -p "Enter APP_INVENTORY_ENDPOINT (default: http://inventory-svc.internal:8081/rooms): " APP_INVENTORY_ENDPOINT
APP_INVENTORY_ENDPOINT=${APP_INVENTORY_ENDPOINT:-http://inventory-svc.internal:8081/rooms}

read -p "Enter APP_NOTIFICATION_ENDPOINT (default: http://notify.internal:7070/send): " APP_NOTIFICATION_ENDPOINT
APP_NOTIFICATION_ENDPOINT=${APP_NOTIFICATION_ENDPOINT:-http://notify.internal:7070/send}

echo ""
echo "=========================================="
echo "Configuring kubectl for EKS"
echo "=========================================="
echo ""

# Configure kubectl to use EKS cluster
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to configure kubectl for EKS cluster"
    exit 1
fi

# Verify cluster connectivity
echo ""
echo "Verifying cluster connectivity..."
kubectl cluster-info || {
    echo "ERROR: Cannot connect to Kubernetes cluster"
    exit 1
}

echo ""
echo "=========================================="
echo "Updating Kubernetes Manifests"
echo "=========================================="
echo ""

# Create temporary directory for modified manifests
TEMP_DIR=$(mktemp -d)
cp -r kubernetes/* "$TEMP_DIR/"

# Replace placeholders in deployment.yaml
sed -i "s|{{IMAGE_URI}}|$IMAGE_URI|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{SPRING_DATASOURCE_URL}}|$SPRING_DATASOURCE_URL|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{SPRING_DATASOURCE_USERNAME}}|$SPRING_DATASOURCE_USERNAME|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{SPRING_DATASOURCE_PASSWORD}}|$SPRING_DATASOURCE_PASSWORD|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{REDIS_HOST}}|$REDIS_HOST|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{REDIS_PORT}}|$REDIS_PORT|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{REDIS_PASSWORD}}|$REDIS_PASSWORD|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{PAYMENT_API_URL}}|$PAYMENT_API_URL|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{APP_PAYMENT_ENDPOINT}}|$APP_PAYMENT_ENDPOINT|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{APP_INVENTORY_ENDPOINT}}|$APP_INVENTORY_ENDPOINT|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{APP_NOTIFICATION_ENDPOINT}}|$APP_NOTIFICATION_ENDPOINT|g" "$TEMP_DIR/deployment.yaml"

echo "Manifests updated successfully"

echo ""
echo "=========================================="
echo "Deploying to Kubernetes"
echo "=========================================="
echo ""

# Apply Kubernetes manifests in order
echo "Creating namespace..."
kubectl apply -f "$TEMP_DIR/namespace.yaml"

echo ""
echo "Deploying application..."
kubectl apply -f "$TEMP_DIR/deployment.yaml"

echo ""
echo "Creating service..."
kubectl apply -f "$TEMP_DIR/service.yaml"

echo ""
echo "Creating ingress..."
kubectl apply -f "$TEMP_DIR/ingress.yaml"

echo ""
echo "=========================================="
echo "Waiting for Deployment Rollout"
echo "=========================================="
echo ""

# Wait for deployment to complete
kubectl rollout status deployment/resortslite -n resortslite --timeout=5m

if [ $? -ne 0 ]; then
    echo "WARNING: Deployment rollout did not complete successfully"
    echo "Check pod status with: kubectl get pods -n resortslite"
fi

echo ""
echo "=========================================="
echo "Deployment Status"
echo "=========================================="
echo ""

# Display deployment status
kubectl get pods,svc,ingress -n resortslite

echo ""
echo "=========================================="
echo "Deployment Completed Successfully!"
echo "=========================================="
echo ""
echo "Application deployed to namespace: resortslite"
echo ""
echo "To check application logs:"
echo "  kubectl logs -f deployment/resortslite -n resortslite"
echo ""
echo "To check pod status:"
echo "  kubectl get pods -n resortslite"
echo ""
echo "To access the application:"
echo "  kubectl port-forward svc/resortslite-service 8080:80 -n resortslite"
echo "  Then open: http://localhost:8080"
echo ""
echo "To get ingress URL:"
echo "  kubectl get ingress resortslite-ingress -n resortslite"
echo ""

# Cleanup temporary directory
rm -rf "$TEMP_DIR"
