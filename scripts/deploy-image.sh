#!/bin/bash
set -e
set -o pipefail

# =============================================================
# deploy-image.sh — Deploy ResortsLite to Azure AKS
# =============================================================

APP_NAME="resortslite"
NAMESPACE="resortslite"
K8S_DIR="kubernetes"

echo "=============================================="
echo "  ResortsLite — Deploy to Azure AKS"
echo "=============================================="

# ---- Azure / AKS credentials ----
read -rp "Enter Azure Resource Group name: " RESOURCE_GROUP
if [ -z "$RESOURCE_GROUP" ]; then
  echo "ERROR: Resource group cannot be empty." >&2
  exit 1
fi

read -rp "Enter AKS Cluster name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: AKS cluster name cannot be empty." >&2
  exit 1
fi

# ---- Docker image URI ----
read -rp "Enter full Docker image URI (e.g. myregistry.azurecr.io/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Image URI cannot be empty." >&2
  exit 1
fi

echo ""
echo "--- Application Environment Variables ---"
echo "(Press Enter to keep the placeholder value for any variable)"

read -rp "Enter SPRING_DATASOURCE_URL [jdbc:oracle:thin:@localhost:1521:ORCL]: " SPRING_DATASOURCE_URL
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:oracle:thin:@localhost:1521:ORCL}"

read -rp "Enter SPRING_DATASOURCE_USERNAME [admin]: " SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-admin}"

read -rsp "Enter SPRING_DATASOURCE_PASSWORD: " SPRING_DATASOURCE_PASSWORD
echo ""
SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-}"

read -rp "Enter REDIS_HOST [localhost]: " REDIS_HOST
REDIS_HOST="${REDIS_HOST:-localhost}"

read -rp "Enter REDIS_PORT [6380]: " REDIS_PORT
REDIS_PORT="${REDIS_PORT:-6380}"

read -rsp "Enter REDIS_PASSWORD: " REDIS_PASSWORD
echo ""
REDIS_PASSWORD="${REDIS_PASSWORD:-}"

read -rp "Enter REDIS_SSL [true]: " REDIS_SSL
REDIS_SSL="${REDIS_SSL:-true}"

read -rp "Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: " PAYMENT_API_URL
PAYMENT_API_URL="${PAYMENT_API_URL:-http://payment-service:9090/payments/charge}"

read -rp "Enter REPORT_BASE_PATH [/var/reports]: " REPORT_BASE_PATH
REPORT_BASE_PATH="${REPORT_BASE_PATH:-/var/reports}"

read -rp "Enter BACKUP_PATH [/var/backups/nightly]: " BACKUP_PATH
BACKUP_PATH="${BACKUP_PATH:-/var/backups/nightly}"

read -rp "Enter BOOKING_CACHE_TTL_SECONDS [3600]: " BOOKING_CACHE_TTL_SECONDS
BOOKING_CACHE_TTL_SECONDS="${BOOKING_CACHE_TTL_SECONDS:-3600}"

# ---- Configure kubectl for AKS ----
echo ""
echo "Configuring kubectl for AKS cluster: ${CLUSTER_NAME} ..."
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing

echo "Verifying cluster connectivity ..."
kubectl cluster-info || { echo "ERROR: Cannot connect to AKS cluster." >&2; exit 1; }

# ---- Substitute placeholders in manifests ----
echo ""
echo "Updating Kubernetes manifests with provided values ..."

# Work on copies to avoid modifying originals
cp -r "${K8S_DIR}" /tmp/resortslite-k8s-deploy

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                                         /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{SPRING_DATASOURCE_URL}}|${SPRING_DATASOURCE_URL}|g"                 /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{SPRING_DATASOURCE_USERNAME}}|${SPRING_DATASOURCE_USERNAME}|g"       /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{SPRING_DATASOURCE_PASSWORD}}|${SPRING_DATASOURCE_PASSWORD}|g"       /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST}|g"                                       /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT}|g"                                       /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{REDIS_PASSWORD}}|${REDIS_PASSWORD}|g"                               /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{REDIS_SSL}}|${REDIS_SSL}|g"                                         /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL}|g"                             /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{REPORT_BASE_PATH}}|${REPORT_BASE_PATH}|g"                           /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{BACKUP_PATH}}|${BACKUP_PATH}|g"                                     /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{BOOKING_CACHE_TTL_SECONDS}}|${BOOKING_CACHE_TTL_SECONDS}|g"         /tmp/resortslite-k8s-deploy/deployment.yaml

# ---- Apply manifests ----
echo ""
echo "Applying Kubernetes manifests ..."

echo "[1/4] Applying namespace ..."
kubectl apply -f /tmp/resortslite-k8s-deploy/namespace.yaml

echo "[2/4] Applying deployment ..."
kubectl apply -f /tmp/resortslite-k8s-deploy/deployment.yaml

echo "[3/4] Applying service ..."
kubectl apply -f /tmp/resortslite-k8s-deploy/service.yaml

echo "[4/4] Applying ingress ..."
kubectl apply -f /tmp/resortslite-k8s-deploy/ingress.yaml

# ---- Wait for rollout ----
echo ""
echo "Waiting for deployment rollout ..."
kubectl rollout status deployment/${APP_NAME} -n ${NAMESPACE} --timeout=300s || {
  echo "ERROR: Deployment rollout failed. Running rollback ..."
  kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}
  exit 1
}

# ---- Verify resources ----
echo ""
echo "Verifying deployed resources ..."
kubectl get pods,svc,ingress -n ${NAMESPACE}

# ---- Display access URL ----
echo ""
INGRESS_HOST=$(kubectl get ingress resortslite-ingress -n ${NAMESPACE} -o jsonpath='{.spec.rules[0].host}' 2>/dev/null || echo "resortslite.example.com")
echo "=============================================="
echo "  Deployment Complete!"
echo "  Application URL: http://${INGRESS_HOST}"
echo "  Health Check:    http://${INGRESS_HOST}/actuator/health"
echo "=============================================="
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"

# Cleanup temp files
rm -rf /tmp/resortslite-k8s-deploy
