#!/bin/bash
# Deploys the pushed Resortlite_Mono1 image to AWS EKS with kubectl.
# Run from the repository root: ./scripts/deploy-image.sh
set -e
set -o pipefail

NAME="resortlite-mono1"
NAMESPACE="resortlite-mono1"

command -v aws >/dev/null 2>&1 || { echo "ERROR: the AWS CLI is required"; exit 1; }
command -v kubectl >/dev/null 2>&1 || { echo "ERROR: kubectl is required"; exit 1; }

echo "=== Deploy $NAME to AWS EKS ==="
read -r -p "Enter the AWS region (for example us-east-1): " AWS_REGION
read -r -p "Enter the EKS cluster name: " CLUSTER_NAME
read -r -p "Enter the image URI (registry/repository:tag): " IMAGE_URI
if [ -z "$AWS_REGION" ] || [ -z "$CLUSTER_NAME" ] || [ -z "$IMAGE_URI" ]; then
  echo "ERROR: region, cluster name and image URI are required"
  exit 1
fi

# Environment variables of the application (placeholders in kubernetes/deployment.yaml)
read -r -p "Enter value for APP_PORT (or press Enter to skip) [8080]: " APP_PORT
APP_PORT=${APP_PORT:-8080}
read -r -p "Enter value for SPRING_PROFILES_ACTIVE (or press Enter to skip) [prod]: " SPRING_PROFILES_ACTIVE
SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-prod}
read -r -p "Enter value for REDIS_HOST (or press Enter to skip) [localhost]: " REDIS_HOST
REDIS_HOST=${REDIS_HOST:-localhost}
read -r -p "Enter value for REDIS_PORT (or press Enter to skip) [6379]: " REDIS_PORT
REDIS_PORT=${REDIS_PORT:-6379}
read -r -p "Enter value for REDIS_SSL (or press Enter to skip) [false]: " REDIS_SSL
REDIS_SSL=${REDIS_SSL:-false}
read -r -p "Enter value for PAYMENT_API_URL (or press Enter to skip) [http://payment-svc.internal:9090/payments/charge]: " PAYMENT_API_URL
PAYMENT_API_URL=${PAYMENT_API_URL:-http://payment-svc.internal:9090/payments/charge}
read -r -p "Enter value for REPORT_PATH (or press Enter to skip) [/app/reports]: " REPORT_PATH
REPORT_PATH=${REPORT_PATH:-/app/reports}
read -r -p "Enter value for BACKUP_PATH (or press Enter to skip) [/app/backups]: " BACKUP_PATH
BACKUP_PATH=${BACKUP_PATH:-/app/backups}
read -r -p "Enter value for CACHE_TTL (or press Enter to skip) [1h]: " CACHE_TTL
CACHE_TTL=${CACHE_TTL:-1h}

# Credential (not echoed)
read -r -s -p "Enter value for REDIS_PASSWORD (ElastiCache auth token, empty if none): " REDIS_PASSWORD
echo

if [ "$APP_PORT" != "8080" ]; then
  echo "WARNING: the manifests use container port 8080; change containerPort, the probes and the Service if APP_PORT is not 8080"
fi

echo "Configuring kubectl for cluster $CLUSTER_NAME ..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME" || { echo "ERROR: aws eks update-kubeconfig failed"; exit 1; }
kubectl cluster-info || exit 1

# Work on copies so that the manifests in the repository keep their placeholders
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
cp -R kubernetes "$WORK_DIR/kubernetes"
DEPLOY_FILE="$WORK_DIR/kubernetes/deployment.yaml"

sed -i.bak 's|{{IMAGE_URI}}|'"$IMAGE_URI"'|g' "$DEPLOY_FILE"
sed -i.bak 's|{{NAMESPACE}}|'"$NAMESPACE"'|g' "$DEPLOY_FILE"
sed -i.bak 's|{{APP_PORT}}|'"$APP_PORT"'|g' "$DEPLOY_FILE"
sed -i.bak 's|{{SPRING_PROFILES_ACTIVE}}|'"$SPRING_PROFILES_ACTIVE"'|g' "$DEPLOY_FILE"
sed -i.bak 's|{{REDIS_HOST}}|'"$REDIS_HOST"'|g' "$DEPLOY_FILE"
sed -i.bak 's|{{REDIS_PORT}}|'"$REDIS_PORT"'|g' "$DEPLOY_FILE"
sed -i.bak 's|{{REDIS_SSL}}|'"$REDIS_SSL"'|g' "$DEPLOY_FILE"
sed -i.bak 's|{{PAYMENT_API_URL}}|'"$PAYMENT_API_URL"'|g' "$DEPLOY_FILE"
sed -i.bak 's|{{REPORT_PATH}}|'"$REPORT_PATH"'|g' "$DEPLOY_FILE"
sed -i.bak 's|{{BACKUP_PATH}}|'"$BACKUP_PATH"'|g' "$DEPLOY_FILE"
sed -i.bak 's|{{CACHE_TTL}}|'"$CACHE_TTL"'|g' "$DEPLOY_FILE"
rm -f "$DEPLOY_FILE.bak"

echo "Applying the namespace ..."
kubectl apply -f "$WORK_DIR/kubernetes/namespace.yaml" || { echo "ERROR: namespace apply failed"; exit 1; }

echo "Creating the Secret $NAME-secrets ..."
kubectl create secret generic "$NAME-secrets" -n "$NAMESPACE" \
  --from-literal=REDIS_PASSWORD="$REDIS_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f - || { echo "ERROR: secret creation failed"; exit 1; }

echo "Applying the deployment, service and ingress ..."
kubectl apply -f "$DEPLOY_FILE" || { echo "ERROR: deployment apply failed"; exit 1; }
kubectl apply -f "$WORK_DIR/kubernetes/service.yaml" || { echo "ERROR: service apply failed"; exit 1; }
kubectl apply -f "$WORK_DIR/kubernetes/ingress.yaml" || { echo "ERROR: ingress apply failed"; exit 1; }

echo "Waiting for the rollout ..."
if ! kubectl rollout status deployment/$NAME -n $NAMESPACE --timeout=300s; then
  echo "ERROR: the rollout failed. Roll back with:"
  echo "  kubectl rollout undo deployment/$NAME -n $NAMESPACE"
  echo "  kubectl describe pods -n $NAMESPACE"
  echo "  kubectl logs deployment/$NAME -n $NAMESPACE"
  exit 1
fi

kubectl get pods,svc,ingress -n $NAMESPACE

INGRESS_HOST=$(kubectl get ingress $NAME-ingress -n $NAMESPACE -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)
if [ -n "$INGRESS_HOST" ]; then
  echo "Application URL: http://$INGRESS_HOST (Host header $NAME.example.com)"
else
  echo "The load balancer address is not ready yet. Check again with: kubectl get ingress -n $NAMESPACE"
fi
echo "To roll back: kubectl rollout undo deployment/$NAME -n $NAMESPACE"
