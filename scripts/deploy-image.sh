#!/bin/bash
set -e
set -o pipefail

APP_NAME="resortslite"
NAMESPACE="resortslite"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_SRC="$SCRIPT_DIR/../kubernetes"

read -p "AWS region (e.g. us-east-1): " AWS_REGION
[ -n "$AWS_REGION" ] || { echo "AWS region is required" >&2; exit 1; }
read -p "EKS cluster name: " CLUSTER_NAME
[ -n "$CLUSTER_NAME" ] || { echo "Cluster name is required" >&2; exit 1; }
read -p "Docker image URI (registry/repo:tag): " IMAGE_URI
[ -n "$IMAGE_URI" ] || { echo "Image URI is required" >&2; exit 1; }

echo "--- Application configuration (press Enter to accept default) ---"
read -p "SPRING_DATASOURCE_URL [jdbc:h2:mem:resortsdb;DB_CLOSE_DELAY=-1]: " SPRING_DATASOURCE_URL
SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL:-jdbc:h2:mem:resortsdb;DB_CLOSE_DELAY=-1}
read -p "SPRING_DATASOURCE_USERNAME [sa]: " SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_USERNAME=${SPRING_DATASOURCE_USERNAME:-sa}
read -s -p "SPRING_DATASOURCE_PASSWORD (or press Enter to skip): " SPRING_DATASOURCE_PASSWORD; echo
read -p "REDIS_HOST (ElastiCache endpoint) [localhost]: " REDIS_HOST
REDIS_HOST=${REDIS_HOST:-localhost}
read -p "REDIS_PORT [6379]: " REDIS_PORT
REDIS_PORT=${REDIS_PORT:-6379}
read -p "REDIS_SSL_ENABLED [false]: " REDIS_SSL_ENABLED
REDIS_SSL_ENABLED=${REDIS_SSL_ENABLED:-false}
read -s -p "REDIS_PASSWORD (or press Enter to skip): " REDIS_PASSWORD; echo
read -p "PAYMENT_API_URL [http://payment-svc:9090/payments/charge]: " PAYMENT_API_URL
PAYMENT_API_URL=${PAYMENT_API_URL:-http://payment-svc:9090/payments/charge}

# Work on a temporary copy so the template manifests stay untouched
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
cp "$K8S_SRC"/*.yaml "$WORK_DIR"/

esc() { printf '%s' "$1" | sed -e 's/[\\|&]/\\&/g'; }

for f in "$WORK_DIR"/*.yaml; do
  sed -i.bak \
    -e "s|{{IMAGE_URI}}|$(esc "$IMAGE_URI")|g" \
    -e "s|{{NAMESPACE}}|$NAMESPACE|g" \
    -e "s|{{SPRING_DATASOURCE_URL}}|$(esc "$SPRING_DATASOURCE_URL")|g" \
    -e "s|{{SPRING_DATASOURCE_USERNAME}}|$(esc "$SPRING_DATASOURCE_USERNAME")|g" \
    -e "s|{{REDIS_HOST}}|$(esc "$REDIS_HOST")|g" \
    -e "s|{{REDIS_PORT}}|$(esc "$REDIS_PORT")|g" \
    -e "s|{{REDIS_SSL_ENABLED}}|$(esc "$REDIS_SSL_ENABLED")|g" \
    -e "s|{{PAYMENT_API_URL}}|$(esc "$PAYMENT_API_URL")|g" \
    "$f"
  rm -f "$f.bak"
done

echo ">>> Configuring kubectl for cluster $CLUSTER_NAME..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo ">>> Verifying cluster connectivity..."
kubectl cluster-info || { echo "Cannot connect to cluster" >&2; exit 1; }

echo ">>> Applying namespace..."
kubectl apply -f "$WORK_DIR/namespace.yaml"

echo ">>> Creating/updating secrets..."
kubectl create secret generic resortslite-secrets -n "$NAMESPACE" \
  --from-literal=SPRING_DATASOURCE_PASSWORD="$SPRING_DATASOURCE_PASSWORD" \
  --from-literal=REDIS_PASSWORD="$REDIS_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -

echo ">>> Applying deployment, service, ingress..."
kubectl apply -f "$WORK_DIR/deployment.yaml"
kubectl apply -f "$WORK_DIR/service.yaml"
kubectl apply -f "$WORK_DIR/ingress.yaml"

echo ">>> Waiting for rollout..."
if ! kubectl rollout status deployment/$APP_NAME -n "$NAMESPACE" --timeout=300s; then
  echo "Rollout failed. To roll back run:" >&2
  echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE" >&2
  exit 1
fi

echo ">>> Resources:"
kubectl get pods,svc,ingress -n "$NAMESPACE"

ALB_HOST=$(kubectl get ingress resortslite-ingress -n "$NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)
if [ -n "$ALB_HOST" ]; then
  echo "Application URL: http://$ALB_HOST (Host header: resortslite.example.com)"
else
  echo "Ingress address not yet assigned. Check: kubectl get ingress -n $NAMESPACE"
fi
