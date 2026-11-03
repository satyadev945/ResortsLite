#!/bin/bash
set -e
set -o pipefail

NAMESPACE="test-comp1"
APP_NAME="test-comp1"

read -r -p "Enter Azure resource group: " RESOURCE_GROUP
read -r -p "Enter AKS cluster name: " CLUSTER_NAME
read -r -p "Enter Docker image URI (registry/repository:tag): " IMAGE_URI

if [ -z "$RESOURCE_GROUP" ] || [ -z "$CLUSTER_NAME" ] || [ -z "$IMAGE_URI" ]; then
  echo "Resource group, cluster name, and image URI are required."
  exit 1
fi

read -r -p "Enter value for SPRING_DATASOURCE_URL (or press Enter to skip): " SPRING_DATASOURCE_URL
read -r -p "Enter value for SPRING_DATASOURCE_USERNAME (or press Enter to skip): " SPRING_DATASOURCE_USERNAME
read -r -p "Enter value for SPRING_DATASOURCE_PASSWORD (or press Enter to skip): " SPRING_DATASOURCE_PASSWORD
read -r -p "Enter value for PAYMENT_ENDPOINT (or press Enter to skip): " PAYMENT_ENDPOINT
read -r -p "Enter value for INVENTORY_ENDPOINT (or press Enter to skip): " INVENTORY_ENDPOINT
read -r -p "Enter value for NOTIFICATION_ENDPOINT (or press Enter to skip): " NOTIFICATION_ENDPOINT
read -r -p "Enter value for REDIS_HOST (or press Enter to skip): " REDIS_HOST
read -r -p "Enter value for REDIS_PORT (or press Enter to skip): " REDIS_PORT
read -r -p "Enter value for REDIS_PASSWORD (or press Enter to skip): " REDIS_PASSWORD
read -r -p "Enter value for REDIS_SSL (or press Enter to skip): " REDIS_SSL

TMP_DEPLOYMENT=$(mktemp)
cp kubernetes/deployment.yaml "$TMP_DEPLOYMENT"

sed -i 's|{{IMAGE_URI}}|'"$IMAGE_URI"'|g' "$TMP_DEPLOYMENT"
sed -i 's|{{SPRING_DATASOURCE_URL}}|'"${SPRING_DATASOURCE_URL:-jdbc:h2:mem:resortdb;DB_CLOSE_DELAY=-1}"'|g' "$TMP_DEPLOYMENT"
sed -i 's|{{SPRING_DATASOURCE_USERNAME}}|'"${SPRING_DATASOURCE_USERNAME:-sa}"'|g' "$TMP_DEPLOYMENT"
sed -i 's|{{SPRING_DATASOURCE_PASSWORD}}|'"${SPRING_DATASOURCE_PASSWORD:-}"'|g' "$TMP_DEPLOYMENT"
sed -i 's|{{PAYMENT_ENDPOINT}}|'"${PAYMENT_ENDPOINT:-https://payment.example.internal/charge}"'|g' "$TMP_DEPLOYMENT"
sed -i 's|{{INVENTORY_ENDPOINT}}|'"${INVENTORY_ENDPOINT:-https://inventory.example.internal/rooms}"'|g' "$TMP_DEPLOYMENT"
sed -i 's|{{NOTIFICATION_ENDPOINT}}|'"${NOTIFICATION_ENDPOINT:-https://notify.example.internal/send}"'|g' "$TMP_DEPLOYMENT"
sed -i 's|{{REDIS_HOST}}|'"${REDIS_HOST:-redis.example.internal}"'|g' "$TMP_DEPLOYMENT"
sed -i 's|{{REDIS_PORT}}|'"${REDIS_PORT:-6379}"'|g' "$TMP_DEPLOYMENT"
sed -i 's|{{REDIS_PASSWORD}}|'"${REDIS_PASSWORD:-}"'|g' "$TMP_DEPLOYMENT"
sed -i 's|{{REDIS_SSL}}|'"${REDIS_SSL:-true}"'|g' "$TMP_DEPLOYMENT"

az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing
kubectl cluster-info
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f "$TMP_DEPLOYMENT"
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml
kubectl rollout status deployment/$APP_NAME -n $NAMESPACE
kubectl get pods,svc,ingress -n $NAMESPACE

echo "Application should be available via the ingress host configured in kubernetes/ingress.yaml"
echo "Rollback command: kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
rm -f "$TMP_DEPLOYMENT"
