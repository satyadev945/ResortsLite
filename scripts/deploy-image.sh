#!/bin/bash
set -e
set -o pipefail

APP_NAME="java-msql"
NAMESPACE="java-msql"

echo "=== Deploy $APP_NAME to AWS EKS ==="

read -p "AWS region (e.g. us-east-1): " AWS_REGION
read -p "EKS cluster name: " CLUSTER_NAME
read -p "Docker image URI (registry/repo:tag): " IMAGE_URI
if [ -z "$AWS_REGION" ] || [ -z "$CLUSTER_NAME" ] || [ -z "$IMAGE_URI" ]; then
  echo "Region, cluster name and image URI are required"; exit 1
fi

# Application configuration (press Enter to use the default)
read -p "Enter value for SPRING_DATASOURCE_URL (or press Enter to skip): " V_DB_URL
read -p "Enter value for SPRING_DATASOURCE_USERNAME (or press Enter to skip): " V_DB_USER
read -s -p "Enter value for SPRING_DATASOURCE_PASSWORD (or press Enter to skip): " V_DB_PASS; echo
read -p "Enter value for REDIS_HOST (or press Enter to skip): " V_REDIS_HOST
read -p "Enter value for REDIS_PORT (or press Enter to skip): " V_REDIS_PORT
read -s -p "Enter value for REDIS_PASSWORD (or press Enter to skip): " V_REDIS_PASS; echo
read -p "Enter value for REDIS_SSL_ENABLED (or press Enter to skip): " V_REDIS_SSL
read -p "Enter value for PAYMENT_API_URL (or press Enter to skip): " V_PAYMENT
read -p "Enter value for APP_INVENTORY_ENDPOINT (or press Enter to skip): " V_INVENTORY
read -p "Enter value for APP_NOTIFICATION_ENDPOINT (or press Enter to skip): " V_NOTIFY

V_DB_URL=${V_DB_URL:-"jdbc:sqlserver://db-prod.resorts-internal.com:1433;databaseName=ResortsDB;encrypt=false;trustServerCertificate=true"}
V_DB_USER=${V_DB_USER:-admin}
V_REDIS_HOST=${V_REDIS_HOST:-localhost}
V_REDIS_PORT=${V_REDIS_PORT:-6379}
V_REDIS_SSL=${V_REDIS_SSL:-false}
V_PAYMENT=${V_PAYMENT:-"http://payment-svc:9090/payments/charge"}
V_INVENTORY=${V_INVENTORY:-"http://inventory-svc.internal:8081/rooms"}
V_NOTIFY=${V_NOTIFY:-"http://notify.internal:7070/send"}

# Work on a temporary copy so the template manifests stay intact
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
cp kubernetes/*.yaml "$WORK_DIR/"

esc() { printf '%s' "$1" | sed -e 's/[\\|&]/\\&/g'; }

sed -i.bak \
  -e "s|{{IMAGE_URI}}|$(esc "$IMAGE_URI")|g" \
  -e "s|{{NAMESPACE}}|$NAMESPACE|g" \
  -e "s|{{SPRING_DATASOURCE_URL}}|$(esc "$V_DB_URL")|g" \
  -e "s|{{SPRING_DATASOURCE_USERNAME}}|$(esc "$V_DB_USER")|g" \
  -e "s|{{REDIS_HOST}}|$(esc "$V_REDIS_HOST")|g" \
  -e "s|{{REDIS_PORT}}|$(esc "$V_REDIS_PORT")|g" \
  -e "s|{{REDIS_SSL_ENABLED}}|$(esc "$V_REDIS_SSL")|g" \
  -e "s|{{PAYMENT_API_URL}}|$(esc "$V_PAYMENT")|g" \
  -e "s|{{APP_INVENTORY_ENDPOINT}}|$(esc "$V_INVENTORY")|g" \
  -e "s|{{APP_NOTIFICATION_ENDPOINT}}|$(esc "$V_NOTIFY")|g" \
  "$WORK_DIR/deployment.yaml"

echo "Configuring kubectl..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "Cannot reach cluster"; exit 1; }

echo "Applying manifests..."
kubectl apply -f "$WORK_DIR/namespace.yaml"

# Secrets are created directly (not written to disk or sed-substituted)
kubectl create secret generic ${APP_NAME}-secrets -n "$NAMESPACE" \
  --from-literal=db-password="$V_DB_PASS" \
  --from-literal=redis-password="$V_REDIS_PASS" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl apply -f "$WORK_DIR/deployment.yaml"
kubectl apply -f "$WORK_DIR/service.yaml"
kubectl apply -f "$WORK_DIR/ingress.yaml"

echo "Waiting for rollout..."
if ! kubectl rollout status deployment/$APP_NAME -n "$NAMESPACE" --timeout=300s; then
  echo "Rollout failed. To roll back run:"
  echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
  echo "Inspect logs: kubectl logs -l app=$APP_NAME -n $NAMESPACE --tail=100"
  exit 1
fi

kubectl get pods,svc,ingress -n "$NAMESPACE"

ALB_HOST=$(kubectl get ingress ${APP_NAME}-ingress -n "$NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)
if [ -n "$ALB_HOST" ]; then
  echo "Application URL: http://$ALB_HOST"
else
  echo "Ingress address not yet assigned. Check: kubectl get ingress -n $NAMESPACE"
fi
echo "Rollback if needed: kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
