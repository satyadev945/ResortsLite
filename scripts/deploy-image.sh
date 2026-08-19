#!/bin/bash
set -e
set -o pipefail

# =============================================================
# deploy-image.sh — Deploy ResortsLite to Azure AKS
# =============================================================

APP_NAME="resortslite"
NAMESPACE="resortslite"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "=============================================="
echo "  ResortsLite — Deploy to Azure AKS"
echo "=============================================="
echo ""

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
echo "Press Enter to keep the default value shown in brackets."
echo ""

# Redis
read -rp "Enter REDIS_HOST [redis-master.resortslite.svc.cluster.local]: " REDIS_HOST_VAL
REDIS_HOST_VAL="${REDIS_HOST_VAL:-redis-master.resortslite.svc.cluster.local}"

read -rp "Enter REDIS_PORT [6379]: " REDIS_PORT_VAL
REDIS_PORT_VAL="${REDIS_PORT_VAL:-6379}"

read -rp "Enter BOOKING_CACHE_TTL_SECONDS [3600]: " BOOKING_CACHE_TTL_VAL
BOOKING_CACHE_TTL_VAL="${BOOKING_CACHE_TTL_VAL:-3600}"

# Azure Service Bus
read -rp "Enter AZURE_SERVICE_BUS_CONNECTION_STRING (or press Enter to skip): " SB_CONN_STR
SB_CONN_STR="${SB_CONN_STR:-}"

read -rp "Enter AZURE_SERVICE_BUS_BOOKING_QUEUE [booking-events-queue]: " SB_BOOKING_QUEUE
SB_BOOKING_QUEUE="${SB_BOOKING_QUEUE:-booking-events-queue}"

read -rp "Enter AZURE_SERVICE_BUS_REPORT_QUEUE [report-events-queue]: " SB_REPORT_QUEUE
SB_REPORT_QUEUE="${SB_REPORT_QUEUE:-report-events-queue}"

# JWT
read -rsp "Enter JWT_SECRET_KEY (or press Enter to use default — NOT recommended for production): " JWT_KEY
echo ""
JWT_KEY="${JWT_KEY:-default-dev-secret-key-replace-in-production}"

# DB Host
read -rp "Enter DB_HOST [localhost]: " DB_HOST_VAL
DB_HOST_VAL="${DB_HOST_VAL:-localhost}"

echo ""
echo "=============================================="
echo "  Configuring kubectl for AKS cluster ..."
echo "=============================================="
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing
if [ $? -ne 0 ]; then
  echo "ERROR: Failed to get AKS credentials." >&2
  exit 1
fi

echo ""
echo "Verifying cluster connectivity ..."
kubectl cluster-info || { echo "ERROR: Cannot connect to AKS cluster." >&2; exit 1; }

echo ""
echo "=============================================="
echo "  Updating Kubernetes manifests ..."
echo "=============================================="

K8S_DIR="${PROJECT_ROOT}/kubernetes"

# Work on copies to avoid modifying originals
DEPLOY_TMP=$(mktemp -d)
cp "${K8S_DIR}/namespace.yaml"  "${DEPLOY_TMP}/namespace.yaml"
cp "${K8S_DIR}/deployment.yaml" "${DEPLOY_TMP}/deployment.yaml"
cp "${K8S_DIR}/service.yaml"    "${DEPLOY_TMP}/service.yaml"
cp "${K8S_DIR}/ingress.yaml"    "${DEPLOY_TMP}/ingress.yaml"

# Replace all placeholders using pipe delimiter
sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                                           "${DEPLOY_TMP}/deployment.yaml"
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST_VAL}|g"                                     "${DEPLOY_TMP}/deployment.yaml"
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT_VAL}|g"                                     "${DEPLOY_TMP}/deployment.yaml"
sed -i "s|{{BOOKING_CACHE_TTL_SECONDS}}|${BOOKING_CACHE_TTL_VAL}|g"               "${DEPLOY_TMP}/deployment.yaml"
sed -i "s|{{AZURE_SERVICE_BUS_CONNECTION_STRING}}|${SB_CONN_STR}|g"               "${DEPLOY_TMP}/deployment.yaml"
sed -i "s|{{AZURE_SERVICE_BUS_BOOKING_QUEUE}}|${SB_BOOKING_QUEUE}|g"              "${DEPLOY_TMP}/deployment.yaml"
sed -i "s|{{AZURE_SERVICE_BUS_REPORT_QUEUE}}|${SB_REPORT_QUEUE}|g"                "${DEPLOY_TMP}/deployment.yaml"
sed -i "s|{{JWT_SECRET_KEY}}|${JWT_KEY}|g"                                         "${DEPLOY_TMP}/deployment.yaml"
sed -i "s|{{DB_HOST}}|${DB_HOST_VAL}|g"                                            "${DEPLOY_TMP}/deployment.yaml"

echo ""
echo "=============================================="
echo "  Applying Kubernetes manifests ..."
echo "=============================================="

echo "[1/4] Applying namespace ..."
kubectl apply -f "${DEPLOY_TMP}/namespace.yaml"

echo "[2/4] Applying deployment ..."
kubectl apply -f "${DEPLOY_TMP}/deployment.yaml"

echo "[3/4] Applying service ..."
kubectl apply -f "${DEPLOY_TMP}/service.yaml"

echo "[4/4] Applying ingress ..."
kubectl apply -f "${DEPLOY_TMP}/ingress.yaml"

echo ""
echo "Waiting for deployment rollout ..."
kubectl rollout status deployment/${APP_NAME} -n ${NAMESPACE} --timeout=300s
if [ $? -ne 0 ]; then
  echo ""
  echo "ERROR: Deployment rollout failed. Rolling back ..." >&2
  kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}
  echo "Rollback initiated. Check pod logs:"
  echo "  kubectl logs -l app=${APP_NAME} -n ${NAMESPACE} --tail=50"
  exit 1
fi

echo ""
echo "=============================================="
echo "  Verifying deployed resources ..."
echo "=============================================="
kubectl get pods,svc,ingress -n ${NAMESPACE}

echo ""
INGRESS_IP=$(kubectl get ingress resortslite-ingress -n ${NAMESPACE} -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "pending")
echo "=============================================="
echo "  DEPLOYMENT SUCCESSFUL"
echo "  Namespace : ${NAMESPACE}"
echo "  Image     : ${IMAGE_URI}"
echo "  Ingress IP: ${INGRESS_IP}"
echo "  App URL   : http://resortslite.example.com"
echo "  Health    : http://resortslite.example.com/actuator/health"
echo "=============================================="

# Clean up temp directory
rm -rf "${DEPLOY_TMP}"
