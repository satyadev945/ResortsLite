#!/bin/bash
set -e
set -o pipefail

APP_NAME="resortslite"
NAMESPACE="resortslite"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_SRC_DIR="$SCRIPT_DIR/../kubernetes"

echo "=== Deploy $APP_NAME to Azure AKS ==="

for tool in az kubectl; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "ERROR: '$tool' is not installed or not in PATH"
    exit 1
  fi
done

prompt_required() {
  local var_name="$1" message="$2" value=""
  while [ -z "$value" ]; do
    read -r -p "$message: " value
  done
  printf -v "$var_name" '%s' "$value"
}

prompt_secret_required() {
  local var_name="$1" message="$2" value=""
  while [ -z "$value" ]; do
    read -r -s -p "$message: " value
    echo ""
  done
  printf -v "$var_name" '%s' "$value"
}

prompt_default() {
  local var_name="$1" message="$2" default="$3" value=""
  read -r -p "$message [$default]: " value
  printf -v "$var_name" '%s' "${value:-$default}"
}

prompt_required RESOURCE_GROUP "Enter Azure resource group"
prompt_required CLUSTER_NAME "Enter AKS cluster name"
prompt_required IMAGE_URI "Enter Docker image URI (e.g. myacr.azurecr.io/resortslite:latest)"

echo ""
echo "--- Application configuration ---"
prompt_required DB_URL "Enter SPRING_DATASOURCE_URL (jdbc:sqlserver://host:1433;databaseName=ResortsDB;encrypt=true)"
prompt_required DB_USERNAME "Enter database username"
prompt_secret_required DB_PASSWORD "Enter database password"
prompt_default REDIS_HOST "Enter REDIS_HOST (Azure Cache for Redis)" "localhost"
prompt_default REDIS_PORT "Enter REDIS_PORT" "6380"
prompt_default REDIS_SSL "Enter REDIS_SSL" "true"
read -r -s -p "Enter REDIS_PASSWORD (or press Enter to skip): " REDIS_PASSWORD
echo ""
prompt_default PAYMENT_ENDPOINT "Enter payment endpoint" "http://payment-svc.internal:9090/charge"
prompt_default INVENTORY_ENDPOINT "Enter inventory endpoint" "http://inventory-svc.internal:8081/rooms"
prompt_default NOTIFICATION_ENDPOINT "Enter notification endpoint" "http://notify.internal:7070/send"
prompt_default INGRESS_HOST "Enter ingress host name" "resortslite.example.com"

# Work on a copy so the template manifests keep their {{PLACEHOLDERS}}
RENDER_DIR="$(mktemp -d)"
trap 'rm -rf "$RENDER_DIR"' EXIT
cp "$K8S_SRC_DIR"/*.yaml "$RENDER_DIR"/

yaml_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }
sed_escape() { printf '%s' "$1" | sed -e 's/[\\|&]/\\&/g'; }

render() {
  local key="$1" value="$2" escaped f
  escaped="$(sed_escape "$(yaml_escape "$value")")"
  for f in "$RENDER_DIR"/*.yaml; do
    sed "s|{{${key}}}|${escaped}|g" "$f" > "$f.tmp"
    mv "$f.tmp" "$f"
  done
}

render IMAGE_URI "$IMAGE_URI"
render NAMESPACE "$NAMESPACE"
render DB_URL "$DB_URL"
render DB_USERNAME "$DB_USERNAME"
render DB_PASSWORD "$DB_PASSWORD"
render REDIS_HOST "$REDIS_HOST"
render REDIS_PORT "$REDIS_PORT"
render REDIS_SSL "$REDIS_SSL"
render REDIS_PASSWORD "$REDIS_PASSWORD"
render PAYMENT_ENDPOINT "$PAYMENT_ENDPOINT"
render INVENTORY_ENDPOINT "$INVENTORY_ENDPOINT"
render NOTIFICATION_ENDPOINT "$NOTIFICATION_ENDPOINT"
render INGRESS_HOST "$INGRESS_HOST"

if grep -q '{{[A-Z_]*}}' "$RENDER_DIR"/*.yaml; then
  echo "ERROR: unresolved placeholders remain in manifests"
  grep -n '{{[A-Z_]*}}' "$RENDER_DIR"/*.yaml
  exit 1
fi

echo "Configuring kubectl for $CLUSTER_NAME ..."
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing

echo "Verifying cluster connectivity ..."
kubectl cluster-info || { echo "ERROR: cannot reach cluster"; exit 1; }

echo "Applying manifests ..."
kubectl apply -f "$RENDER_DIR/namespace.yaml"
kubectl apply -f "$RENDER_DIR/deployment.yaml"
kubectl apply -f "$RENDER_DIR/service.yaml"
kubectl apply -f "$RENDER_DIR/ingress.yaml"

echo "Waiting for rollout ..."
if ! kubectl rollout status deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=300s; then
  echo "ERROR: rollout failed. Inspect with:"
  echo "  kubectl describe pods -n $NAMESPACE"
  echo "  kubectl logs -l app=$APP_NAME -n $NAMESPACE --tail=100"
  echo "Roll back with:"
  echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
  exit 1
fi

echo ""
kubectl get pods,svc,ingress -n "$NAMESPACE"

INGRESS_IP="$(kubectl get ingress resortslite-ingress -n "$NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)"
echo ""
if [ -n "$INGRESS_IP" ]; then
  echo "Application URL: http://$INGRESS_HOST (ingress IP: $INGRESS_IP)"
else
  echo "Ingress IP not yet assigned. Check: kubectl get ingress -n $NAMESPACE"
  echo "Expected URL: http://$INGRESS_HOST"
fi
echo "Rollback if needed: kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
