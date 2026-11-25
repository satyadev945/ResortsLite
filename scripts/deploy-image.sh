#!/bin/bash
set -e
set -o pipefail

cd "$(dirname "$0")/.."

APP_NAME="resortslite"
NAMESPACE="resortslite"

echo "=== ResortsLite - Deploy to AWS EKS ==="

for tool in aws kubectl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: $tool is not installed"; exit 1; }
done

read -r -p "AWS region [us-east-1]: " AWS_REGION
AWS_REGION=${AWS_REGION:-us-east-1}
read -r -p "EKS cluster name: " CLUSTER_NAME
[ -n "$CLUSTER_NAME" ] || { echo "ERROR: cluster name is required"; exit 1; }
read -r -p "Docker image URI (registry/repo:tag): " IMAGE_URI
[ -n "$IMAGE_URI" ] || { echo "ERROR: image URI is required"; exit 1; }

echo "Application configuration (press Enter to accept defaults / skip):"
read -r -p "Enter value for SPRING_DATASOURCE_URL (or press Enter to skip) [jdbc:sqlserver://db-host:1433;databaseName=ResortsDB;encrypt=false;trustServerCertificate=true]: " SPRING_DATASOURCE_URL
SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL:-jdbc:sqlserver://db-host:1433;databaseName=ResortsDB;encrypt=false;trustServerCertificate=true}
read -r -p "Enter value for SPRING_DATASOURCE_USERNAME (or press Enter to skip): " SPRING_DATASOURCE_USERNAME
read -r -s -p "Enter value for SPRING_DATASOURCE_PASSWORD (or press Enter to skip): " SPRING_DATASOURCE_PASSWORD
echo
read -r -p "Enter value for REDIS_HOST (or press Enter to skip) [localhost]: " REDIS_HOST
REDIS_HOST=${REDIS_HOST:-localhost}
read -r -p "Enter value for REDIS_PORT (or press Enter to skip) [6379]: " REDIS_PORT
REDIS_PORT=${REDIS_PORT:-6379}
read -r -s -p "Enter value for REDIS_PASSWORD (or press Enter to skip): " REDIS_PASSWORD
echo
read -r -p "Enter value for REDIS_SSL_ENABLED (or press Enter to skip) [false]: " REDIS_SSL_ENABLED
REDIS_SSL_ENABLED=${REDIS_SSL_ENABLED:-false}
read -r -p "Enter value for PAYMENT_API_URL (or press Enter to skip) [http://payment-svc:9090/payments/charge]: " PAYMENT_API_URL
PAYMENT_API_URL=${PAYMENT_API_URL:-http://payment-svc:9090/payments/charge}

# Work on a temp copy so the templates keep their {{PLACEHOLDERS}}
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
cp kubernetes/*.yaml "$WORK_DIR"/

for f in "$WORK_DIR"/*.yaml; do
  sed -i.bak \
    -e "s|{{IMAGE_URI}}|$IMAGE_URI|g" \
    -e "s|{{NAMESPACE}}|$NAMESPACE|g" \
    -e "s|{{SPRING_DATASOURCE_URL}}|$SPRING_DATASOURCE_URL|g" \
    -e "s|{{REDIS_HOST}}|$REDIS_HOST|g" \
    -e "s|{{REDIS_PORT}}|$REDIS_PORT|g" \
    -e "s|{{REDIS_SSL_ENABLED}}|$REDIS_SSL_ENABLED|g" \
    -e "s|{{PAYMENT_API_URL}}|$PAYMENT_API_URL|g" \
    "$f"
  rm -f "$f.bak"
done

echo "Configuring kubectl..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"
kubectl cluster-info || { echo "ERROR: cannot reach cluster"; exit 1; }

echo "Applying namespace..."
kubectl apply -f "$WORK_DIR/namespace.yaml"

echo "Creating/updating secret..."
kubectl create secret generic resortslite-secrets -n "$NAMESPACE" \
  --from-literal=SPRING_DATASOURCE_USERNAME="$SPRING_DATASOURCE_USERNAME" \
  --from-literal=SPRING_DATASOURCE_PASSWORD="$SPRING_DATASOURCE_PASSWORD" \
  --from-literal=REDIS_PASSWORD="$REDIS_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "Applying deployment, service, ingress..."
kubectl apply -f "$WORK_DIR/deployment.yaml"
kubectl apply -f "$WORK_DIR/service.yaml"
kubectl apply -f "$WORK_DIR/ingress.yaml"

echo "Waiting for rollout..."
if ! kubectl rollout status deployment/$APP_NAME -n "$NAMESPACE" --timeout=300s; then
  echo "ERROR: rollout failed. To roll back run:"
  echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
  exit 1
fi

kubectl get pods,svc,ingress -n "$NAMESPACE"

INGRESS_HOST=$(kubectl get ingress resortslite-ingress -n "$NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)
if [ -n "$INGRESS_HOST" ]; then
  echo "Application URL: http://$INGRESS_HOST"
else
  echo "Ingress address not yet assigned. Check: kubectl get ingress -n $NAMESPACE"
fi
