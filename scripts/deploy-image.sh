#!/bin/bash
set -e
set -o pipefail

APP_NAME="resortslite"
NAMESPACE="resortslite"
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

# Escape a value for use in a sed replacement with '|' delimiter
esc() { printf '%s' "$1" | sed -e 's/[\\&|]/\\&/g'; }

read -r -p "AWS region (e.g. us-east-1): " AWS_REGION
read -r -p "EKS cluster name: " CLUSTER_NAME
read -r -p "Docker image URI (full path with tag): " IMAGE_URI
if [ -z "$AWS_REGION" ] || [ -z "$CLUSTER_NAME" ] || [ -z "$IMAGE_URI" ]; then
  echo "Region, cluster name and image URI are required"
  exit 1
fi

echo "Application configuration (press Enter to skip/use default):"
read -r -p "SPRING_DATASOURCE_URL (jdbc:oracle:thin:@host:1521:SID): " SPRING_DATASOURCE_URL
read -r -p "SPRING_DATASOURCE_USERNAME: " SPRING_DATASOURCE_USERNAME
read -r -s -p "SPRING_DATASOURCE_PASSWORD: " SPRING_DATASOURCE_PASSWORD; echo
read -r -p "REDIS_HOST [localhost]: " REDIS_HOST; REDIS_HOST=${REDIS_HOST:-localhost}
read -r -p "REDIS_PORT [6379]: " REDIS_PORT; REDIS_PORT=${REDIS_PORT:-6379}
read -r -s -p "REDIS_PASSWORD: " REDIS_PASSWORD; echo
read -r -p "REDIS_SSL_ENABLED [false]: " REDIS_SSL_ENABLED; REDIS_SSL_ENABLED=${REDIS_SSL_ENABLED:-false}
read -r -p "PAYMENT_API_URL [http://payment-svc:9090/payments/charge]: " PAYMENT_API_URL
PAYMENT_API_URL=${PAYMENT_API_URL:-http://payment-svc:9090/payments/charge}
read -r -p "APP_PAYMENT_ENDPOINT [http://payment-svc:9090/charge]: " APP_PAYMENT_ENDPOINT
APP_PAYMENT_ENDPOINT=${APP_PAYMENT_ENDPOINT:-http://payment-svc:9090/charge}
read -r -p "APP_INVENTORY_ENDPOINT [http://inventory-svc:8081/rooms]: " APP_INVENTORY_ENDPOINT
APP_INVENTORY_ENDPOINT=${APP_INVENTORY_ENDPOINT:-http://inventory-svc:8081/rooms}
read -r -p "APP_NOTIFICATION_ENDPOINT [http://notify:7070/send]: " APP_NOTIFICATION_ENDPOINT
APP_NOTIFICATION_ENDPOINT=${APP_NOTIFICATION_ENDPOINT:-http://notify:7070/send}

cp kubernetes/*.yaml "$WORK_DIR/"

sed -i.bak \
  -e "s|{{IMAGE_URI}}|$(esc "$IMAGE_URI")|g" \
  -e "s|{{NAMESPACE}}|$NAMESPACE|g" \
  -e "s|{{SPRING_DATASOURCE_URL}}|$(esc "$SPRING_DATASOURCE_URL")|g" \
  -e "s|{{SPRING_DATASOURCE_USERNAME}}|$(esc "$SPRING_DATASOURCE_USERNAME")|g" \
  -e "s|{{SPRING_DATASOURCE_PASSWORD}}|$(esc "$SPRING_DATASOURCE_PASSWORD")|g" \
  -e "s|{{REDIS_HOST}}|$(esc "$REDIS_HOST")|g" \
  -e "s|{{REDIS_PORT}}|$(esc "$REDIS_PORT")|g" \
  -e "s|{{REDIS_PASSWORD}}|$(esc "$REDIS_PASSWORD")|g" \
  -e "s|{{REDIS_SSL_ENABLED}}|$(esc "$REDIS_SSL_ENABLED")|g" \
  -e "s|{{PAYMENT_API_URL}}|$(esc "$PAYMENT_API_URL")|g" \
  -e "s|{{APP_PAYMENT_ENDPOINT}}|$(esc "$APP_PAYMENT_ENDPOINT")|g" \
  -e "s|{{APP_INVENTORY_ENDPOINT}}|$(esc "$APP_INVENTORY_ENDPOINT")|g" \
  -e "s|{{APP_NOTIFICATION_ENDPOINT}}|$(esc "$APP_NOTIFICATION_ENDPOINT")|g" \
  "$WORK_DIR/deployment.yaml"

echo "Configuring kubectl for cluster $CLUSTER_NAME..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "Cannot connect to cluster"; exit 1; }

echo "Applying manifests..."
kubectl apply -f "$WORK_DIR/namespace.yaml"
kubectl apply -f "$WORK_DIR/deployment.yaml"
kubectl apply -f "$WORK_DIR/service.yaml"
kubectl apply -f "$WORK_DIR/ingress.yaml"

echo "Waiting for rollout..."
if ! kubectl rollout status deployment/$APP_NAME -n $NAMESPACE --timeout=300s; then
  echo "Rollout failed. To roll back run:"
  echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
  exit 1
fi

kubectl get pods,svc,ingress -n $NAMESPACE

INGRESS_HOST=$(kubectl get ingress resortslite-ingress -n $NAMESPACE -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)
if [ -n "$INGRESS_HOST" ]; then
  echo "Application URL: http://$INGRESS_HOST"
else
  echo "Ingress address not yet assigned. Check: kubectl get ingress -n $NAMESPACE"
fi
