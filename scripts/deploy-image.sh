#!/bin/bash
set -e
set -o pipefail

NAMESPACE="resortslite"
APP_NAME="resortslite"

read -r -p "Enter Azure resource group: " RESOURCE_GROUP
read -r -p "Enter AKS cluster name: " CLUSTER_NAME
read -r -p "Enter Docker image URI (including tag): " IMAGE_URI

if [ -z "$RESOURCE_GROUP" ] || [ -z "$CLUSTER_NAME" ] || [ -z "$IMAGE_URI" ]; then
  echo "Resource group, cluster name, and image URI are required."
  exit 1
fi

read -r -p "Enter value for SPRING_DATASOURCE_URL (or press Enter to skip): " SPRING_DATASOURCE_URL
read -r -p "Enter value for SPRING_DATASOURCE_USERNAME (or press Enter to skip): " SPRING_DATASOURCE_USERNAME
read -r -p "Enter value for SPRING_DATASOURCE_PASSWORD (or press Enter to skip): " SPRING_DATASOURCE_PASSWORD
read -r -p "Enter value for APP_PAYMENT_ENDPOINT (or press Enter to skip): " APP_PAYMENT_ENDPOINT
read -r -p "Enter value for APP_INVENTORY_ENDPOINT (or press Enter to skip): " APP_INVENTORY_ENDPOINT
read -r -p "Enter value for APP_NOTIFICATION_ENDPOINT (or press Enter to skip): " APP_NOTIFICATION_ENDPOINT
read -r -p "Enter value for CACHE_PROVIDER (or press Enter to skip) [redis]: " CACHE_PROVIDER
CACHE_PROVIDER=${CACHE_PROVIDER:-redis}

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT
cp kubernetes/*.yaml "$TMP_DIR"/

sed -i 's|{{IMAGE_URI}}|'"$IMAGE_URI"'|g' "$TMP_DIR/deployment.yaml"
sed -i 's|{{SPRING_DATASOURCE_URL}}|'"${SPRING_DATASOURCE_URL}"'|g' "$TMP_DIR/deployment.yaml"
sed -i 's|{{SPRING_DATASOURCE_USERNAME}}|'"${SPRING_DATASOURCE_USERNAME}"'|g' "$TMP_DIR/deployment.yaml"
sed -i 's|{{SPRING_DATASOURCE_PASSWORD}}|'"${SPRING_DATASOURCE_PASSWORD}"'|g' "$TMP_DIR/deployment.yaml"
sed -i 's|{{APP_PAYMENT_ENDPOINT}}|'"${APP_PAYMENT_ENDPOINT}"'|g' "$TMP_DIR/deployment.yaml"
sed -i 's|{{APP_INVENTORY_ENDPOINT}}|'"${APP_INVENTORY_ENDPOINT}"'|g' "$TMP_DIR/deployment.yaml"
sed -i 's|{{APP_NOTIFICATION_ENDPOINT}}|'"${APP_NOTIFICATION_ENDPOINT}"'|g' "$TMP_DIR/deployment.yaml"
sed -i 's|{{CACHE_PROVIDER}}|'"${CACHE_PROVIDER}"'|g' "$TMP_DIR/deployment.yaml"

echo "Configuring kubectl for AKS cluster..."
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing
kubectl cluster-info

echo "Applying Kubernetes manifests..."
kubectl apply -f "$TMP_DIR/namespace.yaml"
kubectl apply -f "$TMP_DIR/deployment.yaml"
kubectl apply -f "$TMP_DIR/service.yaml"
kubectl apply -f "$TMP_DIR/ingress.yaml"

echo "Waiting for rollout..."
kubectl rollout status deployment/$APP_NAME -n $NAMESPACE

echo "Deployment resources:"
kubectl get pods,svc,ingress -n $NAMESPACE

echo "Application URL: http://resortslite.example.com/"
echo "Rollback command (if needed): kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
