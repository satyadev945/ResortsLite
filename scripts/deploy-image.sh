#!/bin/bash

# ============================================================================
# Deploy ResortsLite to Google Kubernetes Engine (GKE)
# ============================================================================

set -e
set -o pipefail

echo "============================================"
echo "ResortsLite - GKE Deployment Script"
echo "============================================"
echo ""

# Prompt for GCP configuration
echo "=== GCP Configuration ==="
read -p "Enter GCP Project ID: " GCP_PROJECT
read -p "Enter GCP Zone (e.g., us-central1-a): " GCP_ZONE
read -p "Enter GKE Cluster Name: " CLUSTER_NAME

if [ -z "$GCP_PROJECT" ] || [ -z "$GCP_ZONE" ] || [ -z "$CLUSTER_NAME" ]; then
    echo "ERROR: All GCP configuration fields are required!"
    exit 1
fi

echo ""
echo "=== Docker Image Configuration ==="
read -p "Enter Docker Image URI (e.g., us-central1-docker.pkg.dev/project/repo/resortslite:latest): " IMAGE_URI

if [ -z "$IMAGE_URI" ]; then
    echo "ERROR: Docker Image URI is required!"
    exit 1
fi

echo ""
echo "=== Application Configuration ==="
echo "Configure environment variables for external dependencies"
echo "(Press Enter to skip optional values)"
echo ""

# Database Configuration
read -p "Enter Oracle Database URL (e.g., jdbc:oracle:thin:@host:1521:ORCL): " SPRING_DATASOURCE_URL
SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL:-jdbc:oracle:thin:@oracle-db:1521:ORCL}

read -p "Enter Database Username: " SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_USERNAME=${SPRING_DATASOURCE_USERNAME:-admin}

read -sp "Enter Database Password: " SPRING_DATASOURCE_PASSWORD
echo ""
SPRING_DATASOURCE_PASSWORD=${SPRING_DATASOURCE_PASSWORD:-changeme}

# Redis Configuration
read -p "Enter Redis Host (Google Cloud Memorystore): " REDIS_HOST
REDIS_HOST=${REDIS_HOST:-redis-server}

read -p "Enter Redis Port: " REDIS_PORT
REDIS_PORT=${REDIS_PORT:-6379}

read -sp "Enter Redis Password (if any): " REDIS_PASSWORD
echo ""

# External Service Endpoints
read -p "Enter Payment API URL: " PAYMENT_API_URL
PAYMENT_API_URL=${PAYMENT_API_URL:-http://payment-service:9090/payments/charge}

read -p "Enter Inventory Service Endpoint: " APP_INVENTORY_ENDPOINT
APP_INVENTORY_ENDPOINT=${APP_INVENTORY_ENDPOINT:-http://inventory-service:8081/rooms}

read -p "Enter Notification Service Endpoint: " APP_NOTIFICATION_ENDPOINT
APP_NOTIFICATION_ENDPOINT=${APP_NOTIFICATION_ENDPOINT:-http://notification-service:7070/send}

echo ""
echo "============================================"
echo "Configuring kubectl for GKE cluster..."
echo "============================================"

gcloud container clusters get-credentials "$CLUSTER_NAME" --zone "$GCP_ZONE" --project "$GCP_PROJECT"

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to configure kubectl for GKE cluster!"
    exit 1
fi

echo ""
echo "Verifying cluster connectivity..."
kubectl cluster-info || exit 1

echo ""
echo "============================================"
echo "Updating Kubernetes manifests..."
echo "============================================"

# Create temporary directory for processed manifests
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
sed -i "s|{{APP_INVENTORY_ENDPOINT}}|$APP_INVENTORY_ENDPOINT|g" "$TEMP_DIR/deployment.yaml"
sed -i "s|{{APP_NOTIFICATION_ENDPOINT}}|$APP_NOTIFICATION_ENDPOINT|g" "$TEMP_DIR/deployment.yaml"

echo "Manifests updated successfully."

echo ""
echo "============================================"
echo "Deploying to GKE..."
echo "============================================"

# Apply namespace
echo "Creating namespace..."
kubectl apply -f "$TEMP_DIR/namespace.yaml"

# Apply deployment
echo "Deploying application..."
kubectl apply -f "$TEMP_DIR/deployment.yaml"

# Apply service
echo "Creating service..."
kubectl apply -f "$TEMP_DIR/service.yaml"

# Apply ingress
echo "Creating ingress..."
kubectl apply -f "$TEMP_DIR/ingress.yaml"

echo ""
echo "============================================"
echo "Waiting for deployment rollout..."
echo "============================================"

kubectl rollout status deployment/resortslite -n resortslite --timeout=5m

if [ $? -ne 0 ]; then
    echo "WARNING: Deployment rollout did not complete successfully!"
    echo "Check pod status with: kubectl get pods -n resortslite"
fi

echo ""
echo "============================================"
echo "Verifying deployment..."
echo "============================================"

kubectl get pods,svc,ingress -n resortslite

echo ""
echo "============================================"
echo "Deployment Summary"
echo "============================================"
echo "Namespace: resortslite"
echo "Deployment: resortslite"
echo "Service: resortslite-service"
echo "Ingress: resortslite-ingress"
echo ""
echo "To view logs:"
echo "  kubectl logs -f deployment/resortslite -n resortslite"
echo ""
echo "To get pod status:"
echo "  kubectl get pods -n resortslite"
echo ""
echo "To access the application:"
echo "  kubectl port-forward -n resortslite svc/resortslite-service 8080:80"
echo "  Then visit: http://localhost:8080"
echo ""
echo "To get ingress IP (may take a few minutes):"
echo "  kubectl get ingress resortslite-ingress -n resortslite"
echo "============================================"

# Cleanup temporary directory
rm -rf "$TEMP_DIR"

echo ""
echo "Deployment completed successfully!"
