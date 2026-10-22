#!/bin/bash
set -e
set -o pipefail

# =============================================================
# deploy-image.sh — Deploy ResortsLite to Azure AKS
# =============================================================

APP_NAME="resortslite"
NAMESPACE="resortslite"
MANIFESTS_DIR="kubernetes"

echo "=============================================="
echo "  ResortsLite — AKS Deployment Script"
echo "=============================================="
echo ""

# ---- Azure / AKS credentials ----
read -rp "Enter Azure Resource Group name: " RESOURCE_GROUP
if [ -z "$RESOURCE_GROUP" ]; then
  echo "ERROR: Resource group cannot be empty."
  exit 1
fi

read -rp "Enter AKS Cluster name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: AKS cluster name cannot be empty."
  exit 1
fi

read -rp "Enter full Docker image URI (e.g. myregistry.azurecr.io/resortslite:latest): " IMAGE_URI
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
read -rp "Enter REDIS_PORT [6380]: " REDIS_PORT
REDIS_PORT="${REDIS_PORT:-6380}"
read -rsp "Enter REDIS_PASSWORD (Azure Cache for Redis access key): " REDIS_PASSWORD
echo ""
read -rp "Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: " PAYMENT_API_URL
PAYMENT_API_URL="${PAYMENT_API_URL:-http://payment-service:9090/payments/charge}"
read -rp "Enter APP_PAYMENT_ENDPOINT [http://payment-svc:9090/charge]: " APP_PAYMENT_ENDPOINT
APP_PAYMENT_ENDPOINT="${APP_PAYMENT_ENDPOINT:-http://payment-svc:9090/charge}"
read -rp "Enter APP_INVENTORY_ENDPOINT [http://inventory-svc:8081/rooms]: " APP_INVENTORY_ENDPOINT
APP_INVENTORY_ENDPOINT="${APP_INVENTORY_ENDPOINT:-http://inventory-svc:8081/rooms}"
read -rp "Enter APP_NOTIFICATION_ENDPOINT [http://notify-svc:7070/send]: " APP_NOTIFICATION_ENDPOINT
APP_NOTIFICATION_ENDPOINT="${APP_NOTIFICATION_ENDPOINT:-http://notify-svc:7070/send}"
read -rp "Enter REPORT_BASE_PATH [/tmp/reports]: " REPORT_BASE_PATH
REPORT_BASE_PATH="${REPORT_BASE_PATH:-/tmp/reports}"
read -rp "Enter BACKUP_PATH [/tmp/backups]: " BACKUP_PATH
BACKUP_PATH="${BACKUP_PATH:-/tmp/backups}"

echo ""
echo "--- Configuring kubectl for AKS cluster: $CLUSTER_NAME ---"
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing
if [ $? -ne 0 ]; then
  echo "ERROR: Failed to get AKS credentials."
  exit 1
fi

echo "Verifying cluster connectivity ..."
kubectl cluster-info || { echo "ERROR: Cannot connect to AKS cluster."; exit 1; }

echo ""
echo "--- Substituting placeholders in Kubernetes manifests ---"

# Work on copies to avoid modifying originals
cp -r "$MANIFESTS_DIR" /tmp/resortslite-k8s-deploy

DEPLOY_FILE="/tmp/resortslite-k8s-deploy/deployment.yaml"

sed -i 's|{{IMAGE_URI}}|'"$IMAGE_URI"'|g'                                         "$DEPLOY_FILE"
sed -i 's|{{SPRING_DATASOURCE_URL}}|'"$SPRING_DATASOURCE_URL"'|g'                 "$DEPLOY_FILE"
sed -i 's|{{SPRING_DATASOURCE_USERNAME}}|'"$SPRING_DATASOURCE_USERNAME"'|g'       "$DEPLOY_FILE"
sed -i 's|{{SPRING_DATASOURCE_PASSWORD}}|'"$SPRING_DATASOURCE_PASSWORD"'|g'       "$DEPLOY_FILE"
sed -i 's|{{REDIS_HOST}}|'"$REDIS_HOST"'|g'                                       "$DEPLOY_FILE"
sed -i 's|{{REDIS_PORT}}|'"$REDIS_PORT"'|g'                                       "$DEPLOY_FILE"
sed -i 's|{{REDIS_PASSWORD}}|'"$REDIS_PASSWORD"'|g'                               "$DEPLOY_FILE"
sed -i 's|{{PAYMENT_API_URL}}|'"$PAYMENT_API_URL"'|g'                             "$DEPLOY_FILE"
sed -i 's|{{APP_PAYMENT_ENDPOINT}}|'"$APP_PAYMENT_ENDPOINT"'|g'                   "$DEPLOY_FILE"
sed -i 's|{{APP_INVENTORY_ENDPOINT}}|'"$APP_INVENTORY_ENDPOINT"'|g'               "$DEPLOY_FILE"
sed -i 's|{{APP_NOTIFICATION_ENDPOINT}}|'"$APP_NOTIFICATION_ENDPOINT"'|g'         "$DEPLOY_FILE"
sed -i 's|{{REPORT_BASE_PATH}}|'"$REPORT_BASE_PATH"'|g'                           "$DEPLOY_FILE"
sed -i 's|{{BACKUP_PATH}}|'"$BACKUP_PATH"'|g'                                     "$DEPLOY_FILE"

echo ""
echo "--- Applying Kubernetes manifests ---"
echo "Applying namespace ..."
kubectl apply -f /tmp/resortslite-k8s-deploy/namespace.yaml

echo "Applying deployment ..."
kubectl apply -f /tmp/resortslite-k8s-deploy/deployment.yaml

echo "Applying service ..."
kubectl apply -f /tmp/resortslite-k8s-deploy/service.yaml

echo "Applying ingress ..."
kubectl apply -f /tmp/resortslite-k8s-deploy/ingress.yaml

echo ""
echo "--- Waiting for deployment rollout ---"
kubectl rollout status deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=300s
if [ $? -ne 0 ]; then
  echo "ERROR: Deployment rollout failed. Initiating rollback ..."
  kubectl rollout undo deployment/"$APP_NAME" -n "$NAMESPACE"
  echo "Rollback initiated. Check pod status with: kubectl get pods -n $NAMESPACE"
  exit 1
fi

echo ""
echo "--- Verifying deployed resources ---"
kubectl get pods,svc,ingress -n "$NAMESPACE"

echo ""
echo "--- Application Access ---"
INGRESS_IP=$(kubectl get ingress resortslite-ingress -n "$NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "pending")
if [ "$INGRESS_IP" = "pending" ] || [ -z "$INGRESS_IP" ]; then
  echo "Ingress IP is still being assigned. Run the following to check:"
  echo "  kubectl get ingress resortslite-ingress -n $NAMESPACE"
else
  echo "Application URL: http://$INGRESS_IP"
  echo "Health Check:    http://$INGRESS_IP/actuator/health"
fi

echo ""
echo "=============================================="
echo "  Deployment completed successfully!"
echo "  Namespace: $NAMESPACE"
echo "  Image:     $IMAGE_URI"
echo "=============================================="
echo ""
echo "Useful commands:"
echo "  kubectl get pods -n $NAMESPACE"
echo "  kubectl logs -f deployment/$APP_NAME -n $NAMESPACE"
echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE  # rollback"

# Cleanup temp files
rm -rf /tmp/resortslite-k8s-deploy
