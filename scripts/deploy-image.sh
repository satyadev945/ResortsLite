#!/bin/bash

# Deploy ResortsLite to AWS EKS
# This script configures kubectl and deploys the application to EKS

set -e
set -o pipefail

echo "=========================================="
echo "AWS EKS Deployment Script - ResortsLite"
echo "=========================================="
echo ""

# Prompt for AWS EKS configuration
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

# Prompt for environment variables
echo ""
echo "=== Application Configuration ==="
echo "Enter values for environment variables (or press Enter to skip):"
echo ""

read -p "DATABASE_URL (Oracle JDBC URL): " DATABASE_URL
read -p "DATABASE_USERNAME: " DATABASE_USERNAME
read -sp "DATABASE_PASSWORD: " DATABASE_PASSWORD
echo ""

read -p "REDIS_HOST (ElastiCache endpoint): " REDIS_HOST
read -p "REDIS_PORT (default: 6379): " REDIS_PORT
REDIS_PORT=${REDIS_PORT:-6379}
read -sp "REDIS_PASSWORD: " REDIS_PASSWORD
echo ""

read -p "PAYMENT_API_URL (default: http://payment-service:9090/payments/charge): " PAYMENT_API_URL
PAYMENT_API_URL=${PAYMENT_API_URL:-http://payment-service:9090/payments/charge}

# Configure kubectl for EKS
echo ""
echo "=========================================="
echo "Configuring kubectl for EKS"
echo "=========================================="
echo ""

aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to configure kubectl for EKS cluster"
    exit 1
fi

# Verify cluster connectivity
echo ""
echo "Verifying cluster connectivity..."
kubectl cluster-info || {
    echo "ERROR: Cannot connect to EKS cluster"
    exit 1
}

# Update Kubernetes manifests with actual values
echo ""
echo "=========================================="
echo "Updating Kubernetes Manifests"
echo "=========================================="
echo ""

# Create temporary directory for processed manifests
TEMP_DIR=$(mktemp -d)
cp -r kubernetes/* "$TEMP_DIR/"

# Replace placeholders in deployment.yaml
sed -i "s|{{IMAGE_URI}}|$IMAGE_URI|g" "$TEMP_DIR/deployment.yaml"

if [ -n "$DATABASE_URL" ]; then
    sed -i "s|{{DATABASE_URL}}|$DATABASE_URL|g" "$TEMP_DIR/deployment.yaml"
else
    sed -i "s|{{DATABASE_URL}}|jdbc:oracle:thin:@localhost:1521:ORCL|g" "$TEMP_DIR/deployment.yaml"
fi

if [ -n "$DATABASE_USERNAME" ]; then
    sed -i "s|{{DATABASE_USERNAME}}|$DATABASE_USERNAME|g" "$TEMP_DIR/deployment.yaml"
else
    sed -i "s|{{DATABASE_USERNAME}}|admin|g" "$TEMP_DIR/deployment.yaml"
fi

if [ -n "$DATABASE_PASSWORD" ]; then
    sed -i "s|{{DATABASE_PASSWORD}}|$DATABASE_PASSWORD|g" "$TEMP_DIR/deployment.yaml"
else
    sed -i "s|{{DATABASE_PASSWORD}}|changeme|g" "$TEMP_DIR/deployment.yaml"
fi

if [ -n "$REDIS_HOST" ]; then
    sed -i "s|{{REDIS_HOST}}|$REDIS_HOST|g" "$TEMP_DIR/deployment.yaml"
else
    sed -i "s|{{REDIS_HOST}}|localhost|g" "$TEMP_DIR/deployment.yaml"
fi

sed -i "s|{{REDIS_PORT}}|$REDIS_PORT|g" "$TEMP_DIR/deployment.yaml"

if [ -n "$REDIS_PASSWORD" ]; then
    sed -i "s|{{REDIS_PASSWORD}}|$REDIS_PASSWORD|g" "$TEMP_DIR/deployment.yaml"
else
    sed -i "s|{{REDIS_PASSWORD}}||g" "$TEMP_DIR/deployment.yaml"
fi

sed -i "s|{{PAYMENT_API_URL}}|$PAYMENT_API_URL|g" "$TEMP_DIR/deployment.yaml"

echo "Manifests updated successfully"

# Apply Kubernetes manifests
echo ""
echo "=========================================="
echo "Deploying to EKS"
echo "=========================================="
echo ""

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

# Wait for deployment rollout
echo ""
echo "=========================================="
echo "Waiting for Deployment Rollout"
echo "=========================================="
echo ""

kubectl rollout status deployment/resortslite -n resortslite --timeout=5m

if [ $? -ne 0 ]; then
    echo "WARNING: Deployment rollout did not complete successfully"
    echo "Check pod status with: kubectl get pods -n resortslite"
fi

# Verify deployment
echo ""
echo "=========================================="
echo "Deployment Status"
echo "=========================================="
echo ""

kubectl get pods,svc,ingress -n resortslite

# Get ingress URL
echo ""
echo "=========================================="
echo "Application Access Information"
echo "=========================================="
echo ""

INGRESS_ADDRESS=$(kubectl get ingress resortslite-ingress -n resortslite -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "pending")

if [ "$INGRESS_ADDRESS" != "pending" ] && [ -n "$INGRESS_ADDRESS" ]; then
    echo "Application URL: http://$INGRESS_ADDRESS"
    echo "Health Check: http://$INGRESS_ADDRESS/actuator/health"
else
    echo "Ingress is being provisioned. Check status with:"
    echo "kubectl get ingress -n resortslite"
fi

# Cleanup temporary directory
rm -rf "$TEMP_DIR"

echo ""
echo "=========================================="
echo "Deployment Completed Successfully!"
echo "=========================================="
echo ""
echo "Useful commands:"
echo "  View pods:        kubectl get pods -n resortslite"
echo "  View logs:        kubectl logs -f deployment/resortslite -n resortslite"
echo "  View services:    kubectl get svc -n resortslite"
echo "  View ingress:     kubectl get ingress -n resortslite"
echo "  Describe pod:     kubectl describe pod <pod-name> -n resortslite"
echo ""
echo "To rollback deployment:"
echo "  kubectl rollout undo deployment/resortslite -n resortslite"
echo ""
