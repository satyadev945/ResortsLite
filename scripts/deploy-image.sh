#!/bin/bash
set -e
set -o pipefail

# Run from repository root: ./scripts/deploy-image.sh
APP_NAME="resortslite"
NAMESPACE="resortslite"
SECRET_NAME="resortslite-secrets"

for cmd in az kubectl; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: $cmd is required but not installed"; exit 1; }
done

read -r -p "Enter Azure resource group: " RESOURCE_GROUP
[ -n "$RESOURCE_GROUP" ] || { echo "Resource group is required"; exit 1; }
read -r -p "Enter AKS cluster name: " CLUSTER_NAME
[ -n "$CLUSTER_NAME" ] || { echo "AKS cluster name is required"; exit 1; }
read -r -p "Enter Docker image URI (e.g. myacr.azurecr.io/resortslite:latest): " IMAGE_URI
[ -n "$IMAGE_URI" ] || { echo "Image URI is required"; exit 1; }
read -r -p "Enter ingress host [resortslite.example.com]: " INGRESS_HOST
INGRESS_HOST=${INGRESS_HOST:-resortslite.example.com}

echo "--- Application configuration (press Enter to skip/use default) ---"
read -r -p "Enter value for SPRING_DATASOURCE_URL (or press Enter to skip): " DB_URL
read -r -p "Enter value for SPRING_DATASOURCE_USERNAME (or press Enter to skip): " DB_USERNAME
read -r -s -p "Enter value for SPRING_DATASOURCE_PASSWORD (or press Enter to skip): " DB_PASSWORD; echo
read -r -p "Enter value for REDIS_HOST [localhost]: " REDIS_HOST
read -r -p "Enter value for REDIS_PORT [6380]: " REDIS_PORT
read -r -s -p "Enter value for REDIS_PASSWORD (or press Enter to skip): " REDIS_PASSWORD; echo
read -r -p "Enter value for REDIS_SSL_ENABLED [true]: " REDIS_SSL_ENABLED
read -r -p "Enter value for PAYMENT_API_URL (or press Enter for default): " PAYMENT_API_URL
REDIS_HOST=${REDIS_HOST:-localhost}
REDIS_PORT=${REDIS_PORT:-6380}
REDIS_SSL_ENABLED=${REDIS_SSL_ENABLED:-true}
PAYMENT_API_URL=${PAYMENT_API_URL:-http://payment-svc.internal:9090/payments/charge}

# Work on a copy so the template manifests keep their placeholders
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
cp kubernetes/*.yaml "$WORK_DIR"/

esc() { printf '%s' "$1" | sed -e 's/[&|\\]/\\&/g'; }

sed -i 's|{{IMAGE_URI}}|'"$(esc "$IMAGE_URI")"'|g' "$WORK_DIR"/*.yaml
sed -i 's|{{NAMESPACE}}|'"$NAMESPACE"'|g' "$WORK_DIR"/*.yaml
sed -i 's|{{INGRESS_HOST}}|'"$(esc "$INGRESS_HOST")"'|g' "$WORK_DIR"/*.yaml
sed -i 's|{{REDIS_HOST}}|'"$(esc "$REDIS_HOST")"'|g' "$WORK_DIR"/*.yaml
sed -i 's|{{REDIS_PORT}}|'"$(esc "$REDIS_PORT")"'|g' "$WORK_DIR"/*.yaml
sed -i 's|{{REDIS_SSL_ENABLED}}|'"$(esc "$REDIS_SSL_ENABLED")"'|g' "$WORK_DIR"/*.yaml
sed -i 's|{{PAYMENT_API_URL}}|'"$(esc "$PAYMENT_API_URL")"'|g' "$WORK_DIR"/*.yaml

echo "Configuring kubectl for AKS..."
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "Cannot reach cluster"; exit 1; }

echo "Applying namespace..."
kubectl apply -f "$WORK_DIR/namespace.yaml"

# Sensitive values are stored in a Kubernetes Secret (only non-empty values)
SECRET_ARGS=()
[ -n "$DB_URL" ] && SECRET_ARGS+=(--from-literal=SPRING_DATASOURCE_URL="$DB_URL")
[ -n "$DB_USERNAME" ] && SECRET_ARGS+=(--from-literal=SPRING_DATASOURCE_USERNAME="$DB_USERNAME")
[ -n "$DB_PASSWORD" ] && SECRET_ARGS+=(--from-literal=SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD")
[ -n "$REDIS_PASSWORD" ] && SECRET_ARGS+=(--from-literal=REDIS_PASSWORD="$REDIS_PASSWORD")
if [ ${#SECRET_ARGS[@]} -gt 0 ]; then
  echo "Creating/updating secret $SECRET_NAME..."
  kubectl create secret generic "$SECRET_NAME" -n "$NAMESPACE" "${SECRET_ARGS[@]}" --dry-run=client -o yaml | kubectl apply -f -
else
  echo "No secret values supplied; application defaults will be used."
fi

echo "Applying deployment, service, ingress..."
kubectl apply -f "$WORK_DIR/deployment.yaml"
kubectl apply -f "$WORK_DIR/service.yaml"
kubectl apply -f "$WORK_DIR/ingress.yaml"

echo "Waiting for rollout..."
if ! kubectl rollout status deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=300s; then
  echo "ERROR: rollout failed. To roll back run:"
  echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
  echo "Inspect with: kubectl describe pods -n $NAMESPACE ; kubectl logs -l app=$APP_NAME -n $NAMESPACE"
  exit 1
fi

kubectl get pods,svc,ingress -n "$NAMESPACE"

ADDRESS=$(kubectl get ingress resortslite-ingress -n "$NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)
echo "Application URL: http://${INGRESS_HOST}/  (ingress address: ${ADDRESS:-pending})"
echo "Health: http://${INGRESS_HOST}/actuator/health"
