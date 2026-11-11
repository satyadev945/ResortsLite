#!/bin/bash
set -e
set -o pipefail

APP_NAME="resortslite"
NAMESPACE="resortslite"

# Run from the repository root
if [ ! -d "kubernetes" ]; then
  echo "ERROR: kubernetes/ directory not found. Run from the project root (./scripts/deploy-image.sh)."
  exit 1
fi

for tool in aws kubectl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: $tool is not installed or not in PATH."; exit 1; }
done

prompt_required() {  # $1=var name, $2=prompt
  local value=""
  while [ -z "$value" ]; do
    read -r -p "$2: " value
  done
  printf -v "$1" '%s' "$value"
}

read -r -p "Enter AWS region (e.g. us-east-1): " AWS_REGION
read -r -p "Enter EKS cluster name: " CLUSTER_NAME
read -r -p "Enter Docker image URI (registry/repo:tag): " IMAGE_URI
if [ -z "$AWS_REGION" ] || [ -z "$CLUSTER_NAME" ] || [ -z "$IMAGE_URI" ]; then
  echo "ERROR: AWS region, cluster name and image URI are required."
  exit 1
fi

echo "--- Application configuration (replaces hardcoded values in application.properties) ---"
prompt_required SPRING_DATASOURCE_URL "Enter value for SPRING_DATASOURCE_URL (jdbc:sqlserver://host:1433;databaseName=ResortsDB;...)"
prompt_required SPRING_DATASOURCE_USERNAME "Enter value for SPRING_DATASOURCE_USERNAME"
read -r -s -p "Enter value for SPRING_DATASOURCE_PASSWORD: " SPRING_DATASOURCE_PASSWORD
echo
prompt_required REDIS_HOST "Enter value for REDIS_HOST (ElastiCache endpoint)"
read -r -p "Enter value for REDIS_PORT (or press Enter for 6379): " REDIS_PORT
REDIS_PORT=${REDIS_PORT:-6379}
read -r -s -p "Enter value for REDIS_PASSWORD (or press Enter to skip): " REDIS_PASSWORD
echo
read -r -p "Enter value for REDIS_SSL_ENABLED (true/false, Enter for false): " REDIS_SSL_ENABLED
REDIS_SSL_ENABLED=${REDIS_SSL_ENABLED:-false}
read -r -p "Enter ingress host (Enter for resortslite.example.com): " INGRESS_HOST
INGRESS_HOST=${INGRESS_HOST:-resortslite.example.com}

# Escape a value for a YAML double-quoted scalar, then for sed (pipe delimiter)
esc() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | sed -e 's/[\\&|]/\\&/g'
}

rollback_hint() {
  echo ""
  echo "Deployment failed. To roll back run:"
  echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
  echo "To inspect: kubectl describe pods -n $NAMESPACE ; kubectl logs deployment/$APP_NAME -n $NAMESPACE"
}
trap 'rc=$?; if [ $rc -ne 0 ]; then rollback_hint; fi; rm -rf "$WORK_DIR"' EXIT

# Render manifests in a temp dir so templates keep their placeholders
WORK_DIR=$(mktemp -d)
cp kubernetes/*.yaml "$WORK_DIR"/

sed -i.bak \
  -e "s|{{IMAGE_URI}}|$(esc "$IMAGE_URI")|g" \
  -e "s|{{SPRING_DATASOURCE_URL}}|$(esc "$SPRING_DATASOURCE_URL")|g" \
  -e "s|{{SPRING_DATASOURCE_USERNAME}}|$(esc "$SPRING_DATASOURCE_USERNAME")|g" \
  -e "s|{{SPRING_DATASOURCE_PASSWORD}}|$(esc "$SPRING_DATASOURCE_PASSWORD")|g" \
  -e "s|{{REDIS_HOST}}|$(esc "$REDIS_HOST")|g" \
  -e "s|{{REDIS_PORT}}|$(esc "$REDIS_PORT")|g" \
  -e "s|{{REDIS_PASSWORD}}|$(esc "$REDIS_PASSWORD")|g" \
  -e "s|{{REDIS_SSL_ENABLED}}|$(esc "$REDIS_SSL_ENABLED")|g" \
  -e "s|{{INGRESS_HOST}}|$(esc "$INGRESS_HOST")|g" \
  "$WORK_DIR"/*.yaml
rm -f "$WORK_DIR"/*.bak

echo "Configuring kubectl for cluster $CLUSTER_NAME..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: cannot reach cluster."; exit 1; }

echo "Applying manifests..."
kubectl apply -f "$WORK_DIR/namespace.yaml"
kubectl apply -f "$WORK_DIR/deployment.yaml"
kubectl apply -f "$WORK_DIR/service.yaml"
kubectl apply -f "$WORK_DIR/ingress.yaml"

echo "Waiting for rollout..."
kubectl rollout status deployment/$APP_NAME -n $NAMESPACE --timeout=300s

echo "Deployed resources:"
kubectl get pods,svc,ingress -n $NAMESPACE

ALB_HOST=$(kubectl get ingress resortslite-ingress -n $NAMESPACE -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)
if [ -n "$ALB_HOST" ]; then
  echo "Application URL: http://$ALB_HOST (Host header: $INGRESS_HOST)"
else
  echo "Ingress address not yet assigned. Check later with: kubectl get ingress -n $NAMESPACE"
fi
echo "Rollback if needed: kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
