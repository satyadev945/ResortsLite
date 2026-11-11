#!/bin/bash
set -e
set -o pipefail

APP_NAME="resortslite"
NAMESPACE="resortslite"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="${SCRIPT_DIR}/../kubernetes"

echo "=== ResortsLite - Deploy to AWS EKS ==="

read -r -p "AWS region (e.g. us-east-1): " AWS_REGION
[ -z "$AWS_REGION" ] && { echo "AWS region is required"; exit 1; }
read -r -p "EKS cluster name: " CLUSTER_NAME
[ -z "$CLUSTER_NAME" ] && { echo "EKS cluster name is required"; exit 1; }
read -r -p "Docker image URI (full path with tag): " IMAGE_URI
[ -z "$IMAGE_URI" ] && { echo "Image URI is required"; exit 1; }

echo
echo "Optional application configuration (press Enter to skip any value)."
CM_ARGS=()
SECRET_ARGS=()

ask_cfg() {
  local key="$1" val
  read -r -p "Enter value for ${key} (or press Enter to skip): " val
  if [ -n "$val" ]; then CM_ARGS+=("--from-literal=${key}=${val}"); fi
}
ask_secret() {
  local key="$1" val
  read -r -s -p "Enter value for ${key} (or press Enter to skip): " val
  echo
  if [ -n "$val" ]; then SECRET_ARGS+=("--from-literal=${key}=${val}"); fi
}

ask_cfg SPRING_DATASOURCE_URL
ask_cfg SPRING_DATASOURCE_USERNAME
ask_secret SPRING_DATASOURCE_PASSWORD
ask_cfg REDIS_HOST
ask_cfg REDIS_PORT
ask_cfg REDIS_SSL_ENABLED
ask_secret REDIS_PASSWORD
ask_cfg APP_PAYMENT_ENDPOINT
ask_cfg APP_INVENTORY_ENDPOINT
ask_cfg APP_NOTIFICATION_ENDPOINT

# Work on a temporary copy so repository manifests keep their placeholders
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
cp "${K8S_DIR}"/*.yaml "$WORK_DIR"/

sed -i.bak 's|{{IMAGE_URI}}|'"$IMAGE_URI"'|g' "$WORK_DIR/deployment.yaml"
sed -i.bak 's|{{NAMESPACE}}|'"$NAMESPACE"'|g' "$WORK_DIR"/*.yaml
rm -f "$WORK_DIR"/*.bak

echo "Configuring kubectl..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "Cannot reach cluster"; exit 1; }

echo "Applying namespace..."
kubectl apply -f "$WORK_DIR/namespace.yaml"

if [ ${#CM_ARGS[@]} -gt 0 ]; then
  echo "Applying ConfigMap ${APP_NAME}-config..."
  kubectl create configmap "${APP_NAME}-config" -n "$NAMESPACE" "${CM_ARGS[@]}" --dry-run=client -o yaml | kubectl apply -f -
fi
if [ ${#SECRET_ARGS[@]} -gt 0 ]; then
  echo "Applying Secret ${APP_NAME}-secret..."
  kubectl create secret generic "${APP_NAME}-secret" -n "$NAMESPACE" "${SECRET_ARGS[@]}" --dry-run=client -o yaml | kubectl apply -f -
fi

echo "Applying deployment, service, ingress..."
kubectl apply -f "$WORK_DIR/deployment.yaml"
kubectl apply -f "$WORK_DIR/service.yaml"
kubectl apply -f "$WORK_DIR/ingress.yaml"

echo "Waiting for rollout..."
if ! kubectl rollout status deployment/${APP_NAME} -n ${NAMESPACE} --timeout=300s; then
  echo "Rollout failed. To roll back run:"
  echo "  kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"
  echo "Inspect with: kubectl describe pods -n ${NAMESPACE}; kubectl logs deployment/${APP_NAME} -n ${NAMESPACE}"
  exit 1
fi

kubectl get pods,svc,ingress -n ${NAMESPACE}

INGRESS_HOST=$(kubectl get ingress ${APP_NAME}-ingress -n ${NAMESPACE} -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)
if [ -n "$INGRESS_HOST" ]; then
  echo "Application URL: http://${INGRESS_HOST}"
  echo "Health: http://${INGRESS_HOST}/actuator/health"
else
  echo "Ingress address not yet assigned. Check later with: kubectl get ingress -n ${NAMESPACE}"
fi
echo "Rollback if needed: kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"
