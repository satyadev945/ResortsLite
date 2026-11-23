#!/bin/bash
set -e
set -o pipefail

# Run from the repository root.
APP_NAME="resortslite"
NAMESPACE="resortslite"

# Escape a value for use in the replacement part of sed (delimiter is |)
esc() { printf '%s' "$1" | sed -e 's/[\&|]/\\&/g'; }

read -r -p "Enter AWS region (e.g. us-east-1): " AWS_REGION
read -r -p "Enter EKS cluster name: " CLUSTER_NAME
read -r -p "Enter Docker image URI (registry/repo:tag): " IMAGE_URI
if [ -z "$AWS_REGION" ] || [ -z "$CLUSTER_NAME" ] || [ -z "$IMAGE_URI" ]; then
  echo "AWS region, cluster name and image URI are required."; exit 1
fi

echo "--- Application configuration ---"
read -r -p "Enter value for SPRING_DATASOURCE_URL (e.g. jdbc:sqlserver://host:1433;databaseName=ResortsDB;encrypt=true): " SPRING_DATASOURCE_URL
read -r -p "Enter value for SPRING_DATASOURCE_USERNAME: " SPRING_DATASOURCE_USERNAME
read -r -s -p "Enter value for SPRING_DATASOURCE_PASSWORD: " SPRING_DATASOURCE_PASSWORD; echo
if [ -z "$SPRING_DATASOURCE_URL" ] || [ -z "$SPRING_DATASOURCE_USERNAME" ] || [ -z "$SPRING_DATASOURCE_PASSWORD" ]; then
  echo "Database URL, username and password are required."; exit 1
fi
read -r -p "Enter value for REDIS_HOST (ElastiCache endpoint): " REDIS_HOST
if [ -z "$REDIS_HOST" ]; then echo "REDIS_HOST is required."; exit 1; fi
read -r -p "Enter value for REDIS_PORT (or press Enter for 6379): " REDIS_PORT
REDIS_PORT=${REDIS_PORT:-6379}
read -r -s -p "Enter value for REDIS_PASSWORD (or press Enter to skip): " REDIS_PASSWORD; echo
read -r -p "Enter value for REDIS_SSL_ENABLED (true/false, Enter for false): " REDIS_SSL_ENABLED
REDIS_SSL_ENABLED=${REDIS_SSL_ENABLED:-false}
read -r -p "Enter value for PAYMENT_API_URL (or press Enter for default): " PAYMENT_API_URL
PAYMENT_API_URL=${PAYMENT_API_URL:-http://payment-svc:9090/payments/charge}

# Work on a temp copy so the template manifests stay untouched
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
cp -r kubernetes "$WORK_DIR/kubernetes"

for f in "$WORK_DIR"/kubernetes/*.yaml; do
  sed -i \
    -e 's|{{IMAGE_URI}}|'"$(esc "$IMAGE_URI")"'|g' \
    -e 's|{{NAMESPACE}}|'"$NAMESPACE"'|g' \
    -e 's|{{SPRING_DATASOURCE_URL}}|'"$(esc "$SPRING_DATASOURCE_URL")"'|g' \
    -e 's|{{SPRING_DATASOURCE_USERNAME}}|'"$(esc "$SPRING_DATASOURCE_USERNAME")"'|g' \
    -e 's|{{SPRING_DATASOURCE_PASSWORD}}|'"$(esc "$SPRING_DATASOURCE_PASSWORD")"'|g' \
    -e 's|{{REDIS_HOST}}|'"$(esc "$REDIS_HOST")"'|g' \
    -e 's|{{REDIS_PORT}}|'"$(esc "$REDIS_PORT")"'|g' \
    -e 's|{{REDIS_PASSWORD}}|'"$(esc "$REDIS_PASSWORD")"'|g' \
    -e 's|{{REDIS_SSL_ENABLED}}|'"$(esc "$REDIS_SSL_ENABLED")"'|g' \
    -e 's|{{PAYMENT_API_URL}}|'"$(esc "$PAYMENT_API_URL")"'|g' \
    "$f"
done

echo "Configuring kubectl..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "Cannot reach cluster"; exit 1; }

echo "Applying manifests..."
kubectl apply -f "$WORK_DIR/kubernetes/namespace.yaml"
kubectl apply -f "$WORK_DIR/kubernetes/secret.yaml"
kubectl apply -f "$WORK_DIR/kubernetes/deployment.yaml"
kubectl apply -f "$WORK_DIR/kubernetes/service.yaml"
kubectl apply -f "$WORK_DIR/kubernetes/ingress.yaml"

echo "Waiting for rollout..."
if ! kubectl rollout status deployment/${APP_NAME} -n ${NAMESPACE} --timeout=300s; then
  echo "Rollout failed. Inspect with: kubectl describe pods -n ${NAMESPACE}"
  echo "Roll back with: kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"
  exit 1
fi

kubectl get pods,svc,ingress -n ${NAMESPACE}

INGRESS_HOST=$(kubectl get ingress ${APP_NAME}-ingress -n ${NAMESPACE} -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)
if [ -n "$INGRESS_HOST" ]; then
  echo "Application URL: http://${INGRESS_HOST}/ (health: http://${INGRESS_HOST}/actuator/health)"
else
  echo "Ingress address not yet assigned. Check: kubectl get ingress -n ${NAMESPACE}"
fi
echo "Rollback if needed: kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"
