#!/bin/bash
set -e
set -o pipefail

# =============================================================
# deploy-image.sh — Deploy orcappdbmmono to Azure AKS
# =============================================================

APP_NAME="orcappdbmmono"
NAMESPACE="orcappdbmmono"
K8S_DIR="$(cd "$(dirname "$0")/.." && pwd)/kubernetes"

echo "=============================================="
echo "  Deploy to Azure AKS: $APP_NAME"
echo "=============================================="
echo ""

# --- Azure / AKS credentials ---
read -rp "Enter Azure Resource Group name: " RESOURCE_GROUP
if [ -z "$RESOURCE_GROUP" ]; then
    echo "ERROR: Resource group cannot be empty."
    exit 1
fi

read -rp "Enter AKS Cluster name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
    echo "ERROR: Cluster name cannot be empty."
    exit 1
fi

# --- Docker image URI ---
read -rp "Enter full Docker image URI (e.g. myregistry.azurecr.io/orcappdbmmono:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
    echo "ERROR: Image URI cannot be empty."
    exit 1
fi

echo ""
echo "--- Application Environment Variables ---"
echo "Press Enter to skip any variable (placeholder will remain in manifest)."
echo ""

read -rp "Enter SPRING_DATASOURCE_URL (Oracle JDBC URL): " SPRING_DATASOURCE_URL
read -rp "Enter SPRING_DATASOURCE_USERNAME: " SPRING_DATASOURCE_USERNAME
read -rsp "Enter SPRING_DATASOURCE_PASSWORD: " SPRING_DATASOURCE_PASSWORD
echo ""
read -rp "Enter REDIS_HOST (Azure Cache for Redis hostname): " REDIS_HOST
read -rp "Enter REDIS_PORT [default: 6380]: " REDIS_PORT
REDIS_PORT="${REDIS_PORT:-6380}"
read -rsp "Enter REDIS_PASSWORD: " REDIS_PASSWORD
echo ""
read -rp "Enter REDIS_SSL [default: true]: " REDIS_SSL
REDIS_SSL="${REDIS_SSL:-true}"
read -rp "Enter PAYMENT_API_URL: " PAYMENT_API_URL
read -rp "Enter APP_PAYMENT_ENDPOINT: " APP_PAYMENT_ENDPOINT
read -rp "Enter APP_INVENTORY_ENDPOINT: " APP_INVENTORY_ENDPOINT
read -rp "Enter APP_NOTIFICATION_ENDPOINT: " APP_NOTIFICATION_ENDPOINT
read -rp "Enter REPORTS_BASE_PATH [default: /var/reports]: " REPORTS_BASE_PATH
REPORTS_BASE_PATH="${REPORTS_BASE_PATH:-/var/reports}"
read -rp "Enter REPORTS_BACKUP_PATH [default: /var/backups/nightly]: " REPORTS_BACKUP_PATH
REPORTS_BACKUP_PATH="${REPORTS_BACKUP_PATH:-/var/backups/nightly}"

echo ""
echo "--- Configuring kubectl for AKS ---"
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing
echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to AKS cluster."; exit 1; }

echo ""
echo "--- Updating Kubernetes manifests ---"

# Work on a temporary copy to avoid modifying originals
TMP_DIR=$(mktemp -d)
cp -r "$K8S_DIR"/. "$TMP_DIR/"

# Replace all placeholders using pipe delimiter
sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                                   "$TMP_DIR/deployment.yaml"
sed -i "s|{{SPRING_DATASOURCE_URL}}|${SPRING_DATASOURCE_URL}|g"           "$TMP_DIR/deployment.yaml"
sed -i "s|{{SPRING_DATASOURCE_USERNAME}}|${SPRING_DATASOURCE_USERNAME}|g" "$TMP_DIR/deployment.yaml"
sed -i "s|{{SPRING_DATASOURCE_PASSWORD}}|${SPRING_DATASOURCE_PASSWORD}|g" "$TMP_DIR/deployment.yaml"
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST}|g"                                 "$TMP_DIR/deployment.yaml"
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT}|g"                                 "$TMP_DIR/deployment.yaml"
sed -i "s|{{REDIS_PASSWORD}}|${REDIS_PASSWORD}|g"                         "$TMP_DIR/deployment.yaml"
sed -i "s|{{REDIS_SSL}}|${REDIS_SSL}|g"                                   "$TMP_DIR/deployment.yaml"
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL}|g"                       "$TMP_DIR/deployment.yaml"
sed -i "s|{{APP_PAYMENT_ENDPOINT}}|${APP_PAYMENT_ENDPOINT}|g"             "$TMP_DIR/deployment.yaml"
sed -i "s|{{APP_INVENTORY_ENDPOINT}}|${APP_INVENTORY_ENDPOINT}|g"         "$TMP_DIR/deployment.yaml"
sed -i "s|{{APP_NOTIFICATION_ENDPOINT}}|${APP_NOTIFICATION_ENDPOINT}|g"   "$TMP_DIR/deployment.yaml"
sed -i "s|{{REPORTS_BASE_PATH}}|${REPORTS_BASE_PATH}|g"                   "$TMP_DIR/deployment.yaml"
sed -i "s|{{REPORTS_BACKUP_PATH}}|${REPORTS_BACKUP_PATH}|g"               "$TMP_DIR/deployment.yaml"

echo ""
echo "--- Applying Kubernetes manifests ---"
kubectl apply -f "$TMP_DIR/namespace.yaml"
kubectl apply -f "$TMP_DIR/deployment.yaml"
kubectl apply -f "$TMP_DIR/service.yaml"
kubectl apply -f "$TMP_DIR/ingress.yaml"

echo ""
echo "--- Waiting for rollout ---"
kubectl rollout status deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=300s

echo ""
echo "--- Verifying resources ---"
kubectl get pods,svc,ingress -n "$NAMESPACE"

echo ""
INGRESS_HOST=$(kubectl get ingress "${APP_NAME}-ingress" -n "$NAMESPACE" -o jsonpath='{.spec.rules[0].host}' 2>/dev/null || echo "orcappdbmmono.example.com")
echo "=============================================="
echo "  DEPLOYMENT COMPLETE"
echo "  Application URL: http://${INGRESS_HOST}"
echo "  Health check:    http://${INGRESS_HOST}/actuator/health"
echo "=============================================="
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"

# Cleanup temp dir
rm -rf "$TMP_DIR"
