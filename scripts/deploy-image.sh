#!/bin/bash
set -e
set -o pipefail

APP_NAME="resortslite"
NAMESPACE="resortslite"

echo "=== Deploy ${APP_NAME} to Azure AKS ==="

command -v az >/dev/null 2>&1 || { echo "ERROR: Azure CLI (az) is required"; exit 1; }
command -v kubectl >/dev/null 2>&1 || { echo "ERROR: kubectl is required"; exit 1; }
[ -d "kubernetes" ] || { echo "ERROR: kubernetes/ directory not found. Run from the repository root."; exit 1; }

read -r -p "Enter Azure resource group: " RESOURCE_GROUP
[ -n "$RESOURCE_GROUP" ] || { echo "ERROR: resource group is required"; exit 1; }
read -r -p "Enter AKS cluster name: " CLUSTER_NAME
[ -n "$CLUSTER_NAME" ] || { echo "ERROR: cluster name is required"; exit 1; }
read -r -p "Enter Docker image URI (e.g. myacr.azurecr.io/resortslite:latest): " IMAGE_URI
[ -n "$IMAGE_URI" ] || { echo "ERROR: image URI is required"; exit 1; }

echo
echo "Application configuration (press Enter to skip / use default)"
read -r -p "SPRING_DATASOURCE_URL [jdbc:sqlserver://db-prod.resorts-internal.com:1433;databaseName=ResortsDB;encrypt=false;trustServerCertificate=true]: " SPRING_DATASOURCE_URL
SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL:-"jdbc:sqlserver://db-prod.resorts-internal.com:1433;databaseName=ResortsDB;encrypt=false;trustServerCertificate=true"}
read -r -p "SPRING_DATASOURCE_USERNAME [admin]: " SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_USERNAME=${SPRING_DATASOURCE_USERNAME:-admin}
read -r -s -p "SPRING_DATASOURCE_PASSWORD: " SPRING_DATASOURCE_PASSWORD; echo
read -r -p "REDIS_HOST [localhost]: " REDIS_HOST
REDIS_HOST=${REDIS_HOST:-localhost}
read -r -p "REDIS_PORT [6380]: " REDIS_PORT
REDIS_PORT=${REDIS_PORT:-6380}
read -r -p "REDIS_SSL_ENABLED [true]: " REDIS_SSL_ENABLED
REDIS_SSL_ENABLED=${REDIS_SSL_ENABLED:-true}
read -r -s -p "REDIS_PASSWORD: " REDIS_PASSWORD; echo
read -r -p "BOOKING_CACHE_TTL_SECONDS [3600]: " BOOKING_CACHE_TTL_SECONDS
BOOKING_CACHE_TTL_SECONDS=${BOOKING_CACHE_TTL_SECONDS:-3600}
read -r -p "PAYMENT_API_URL [http://payment-svc.internal:9090/payments/charge]: " PAYMENT_API_URL
PAYMENT_API_URL=${PAYMENT_API_URL:-"http://payment-svc.internal:9090/payments/charge"}

# Work on a copy so templates keep their placeholders
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
cp -r kubernetes/. "$WORK_DIR/"

for f in "$WORK_DIR"/*.yaml; do
  sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g" "$f"
  sed -i "s|{{NAMESPACE}}|${NAMESPACE}|g" "$f"
  sed -i "s|{{SPRING_DATASOURCE_URL}}|${SPRING_DATASOURCE_URL}|g" "$f"
  sed -i "s|{{REDIS_HOST}}|${REDIS_HOST}|g" "$f"
  sed -i "s|{{REDIS_PORT}}|${REDIS_PORT}|g" "$f"
  sed -i "s|{{REDIS_SSL_ENABLED}}|${REDIS_SSL_ENABLED}|g" "$f"
  sed -i "s|{{BOOKING_CACHE_TTL_SECONDS}}|${BOOKING_CACHE_TTL_SECONDS}|g" "$f"
  sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL}|g" "$f"
done

echo "Configuring kubectl..."
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: cannot reach cluster"; exit 1; }

echo "Applying namespace..."
kubectl apply -f "$WORK_DIR/namespace.yaml"

echo "Creating/updating secret resortslite-secrets..."
kubectl create secret generic resortslite-secrets -n "$NAMESPACE" \
  --from-literal=SPRING_DATASOURCE_USERNAME="$SPRING_DATASOURCE_USERNAME" \
  --from-literal=SPRING_DATASOURCE_PASSWORD="$SPRING_DATASOURCE_PASSWORD" \
  --from-literal=REDIS_PASSWORD="$REDIS_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "Applying deployment..."
kubectl apply -f "$WORK_DIR/deployment.yaml"
echo "Applying service..."
kubectl apply -f "$WORK_DIR/service.yaml"
echo "Applying ingress..."
kubectl apply -f "$WORK_DIR/ingress.yaml"

echo "Waiting for rollout..."
if ! kubectl rollout status deployment/${APP_NAME} -n "$NAMESPACE" --timeout=300s; then
  echo "ERROR: rollout failed."
  echo "Rollback with: kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"
  echo "Inspect with:  kubectl describe pods -n ${NAMESPACE} ; kubectl logs -l app=${APP_NAME} -n ${NAMESPACE}"
  exit 1
fi

kubectl get pods,svc,ingress -n "$NAMESPACE"

INGRESS_IP=$(kubectl get ingress resortslite-ingress -n "$NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)
if [ -n "$INGRESS_IP" ]; then
  echo "Application URL: http://${INGRESS_IP}/ (host header: resortslite.example.com)"
else
  echo "Ingress address not yet assigned. Check: kubectl get ingress -n ${NAMESPACE}"
fi
echo "Rollback if needed: kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"
