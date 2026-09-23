#!/bin/bash
set -e
set -o pipefail

# =============================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# =============================================================

APP_NAME="resortslite"
NAMESPACE="resortslite"
K8S_DIR="kubernetes"

echo "=============================================="
echo "  ResortsLite — Deploy to AWS EKS"
echo "=============================================="

# ---- AWS / EKS configuration ----
read -rp "Enter AWS region [us-east-1]: " AWS_REGION
AWS_REGION="${AWS_REGION:-us-east-1}"

read -rp "Enter EKS cluster name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: EKS cluster name is required." >&2
  exit 1
fi

read -rp "Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Docker image URI is required." >&2
  exit 1
fi

echo ""
echo "--- Application Environment Variables ---"
echo "Press Enter to skip any variable (placeholder will remain in manifest)."

read -rp "Enter SPRING_DATASOURCE_URL [jdbc:oracle:thin:@localhost:1521:ORCL]: " SPRING_DATASOURCE_URL
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:oracle:thin:@localhost:1521:ORCL}"

read -rp "Enter SPRING_DATASOURCE_USERNAME [admin]: " SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-admin}"

read -rsp "Enter SPRING_DATASOURCE_PASSWORD: " SPRING_DATASOURCE_PASSWORD
echo ""
SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-changeme}"

read -rp "Enter REDIS_HOST (Amazon ElastiCache endpoint) [localhost]: " REDIS_HOST
REDIS_HOST="${REDIS_HOST:-localhost}"

read -rp "Enter REDIS_PORT [6379]: " REDIS_PORT
REDIS_PORT="${REDIS_PORT:-6379}"

read -rsp "Enter REDIS_PASSWORD (leave blank if none): " REDIS_PASSWORD
echo ""

read -rp "Enter APP_PAYMENT_ENDPOINT [http://payment-svc:9090/charge]: " APP_PAYMENT_ENDPOINT
APP_PAYMENT_ENDPOINT="${APP_PAYMENT_ENDPOINT:-http://payment-svc:9090/charge}"

read -rp "Enter APP_INVENTORY_ENDPOINT [http://inventory-svc:8081/rooms]: " APP_INVENTORY_ENDPOINT
APP_INVENTORY_ENDPOINT="${APP_INVENTORY_ENDPOINT:-http://inventory-svc:8081/rooms}"

read -rp "Enter APP_NOTIFICATION_ENDPOINT [http://notify-svc:7070/send]: " APP_NOTIFICATION_ENDPOINT
APP_NOTIFICATION_ENDPOINT="${APP_NOTIFICATION_ENDPOINT:-http://notify-svc:7070/send}"

read -rp "Enter REPORT_BASE_PATH [/var/reports]: " REPORT_BASE_PATH
REPORT_BASE_PATH="${REPORT_BASE_PATH:-/var/reports}"

read -rp "Enter BACKUP_PATH [/var/backups/nightly]: " BACKUP_PATH
BACKUP_PATH="${BACKUP_PATH:-/var/backups/nightly}"

read -rp "Enter BOOKING_CACHE_TTL_SECONDS [3600]: " BOOKING_CACHE_TTL_SECONDS
BOOKING_CACHE_TTL_SECONDS="${BOOKING_CACHE_TTL_SECONDS:-3600}"

# ---- Configure kubectl for EKS ----
echo ""
echo "Configuring kubectl for EKS cluster: ${CLUSTER_NAME} in ${AWS_REGION}..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster." >&2; exit 1; }

# ---- Substitute placeholders in manifests ----
echo ""
echo "Updating Kubernetes manifests with deployment values..."

# Work on copies to avoid modifying originals
TMP_DIR=$(mktemp -d)
cp -r "${K8S_DIR}/." "${TMP_DIR}/"

DEPLOYMENT_YAML="${TMP_DIR}/deployment.yaml"

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                                   "${DEPLOYMENT_YAML}"
sed -i "s|{{SPRING_DATASOURCE_URL}}|${SPRING_DATASOURCE_URL}|g"           "${DEPLOYMENT_YAML}"
sed -i "s|{{SPRING_DATASOURCE_USERNAME}}|${SPRING_DATASOURCE_USERNAME}|g" "${DEPLOYMENT_YAML}"
sed -i "s|{{SPRING_DATASOURCE_PASSWORD}}|${SPRING_DATASOURCE_PASSWORD}|g" "${DEPLOYMENT_YAML}"
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST}|g"                                 "${DEPLOYMENT_YAML}"
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT}|g"                                 "${DEPLOYMENT_YAML}"
sed -i "s|{{REDIS_PASSWORD}}|${REDIS_PASSWORD}|g"                         "${DEPLOYMENT_YAML}"
sed -i "s|{{APP_PAYMENT_ENDPOINT}}|${APP_PAYMENT_ENDPOINT}|g"             "${DEPLOYMENT_YAML}"
sed -i "s|{{APP_INVENTORY_ENDPOINT}}|${APP_INVENTORY_ENDPOINT}|g"         "${DEPLOYMENT_YAML}"
sed -i "s|{{APP_NOTIFICATION_ENDPOINT}}|${APP_NOTIFICATION_ENDPOINT}|g"   "${DEPLOYMENT_YAML}"
sed -i "s|{{REPORT_BASE_PATH}}|${REPORT_BASE_PATH}|g"                     "${DEPLOYMENT_YAML}"
sed -i "s|{{BACKUP_PATH}}|${BACKUP_PATH}|g"                               "${DEPLOYMENT_YAML}"
sed -i "s|{{BOOKING_CACHE_TTL_SECONDS}}|${BOOKING_CACHE_TTL_SECONDS}|g"   "${DEPLOYMENT_YAML}"

# ---- Apply manifests ----
echo ""
echo "Applying Kubernetes manifests..."

echo "  [1/4] Applying namespace..."
kubectl apply -f "${TMP_DIR}/namespace.yaml"

echo "  [2/4] Applying deployment..."
kubectl apply -f "${DEPLOYMENT_YAML}"

echo "  [3/4] Applying service..."
kubectl apply -f "${TMP_DIR}/service.yaml"

echo "  [4/4] Applying ingress..."
kubectl apply -f "${TMP_DIR}/ingress.yaml"

# ---- Wait for rollout ----
echo ""
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/"${APP_NAME}" -n "${NAMESPACE}" --timeout=300s

# ---- Verify ----
echo ""
echo "Verifying deployed resources..."
kubectl get pods,svc,ingress -n "${NAMESPACE}"

# ---- Display URL ----
echo ""
INGRESS_HOST=$(kubectl get ingress "${APP_NAME}-ingress" -n "${NAMESPACE}" \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "<pending>")
echo "=============================================="
echo "  Deployment Complete!"
echo "  Application URL: http://${INGRESS_HOST}"
echo "  Health Check:    http://${INGRESS_HOST}/actuator/health"
echo "=============================================="
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"

# Cleanup temp dir
rm -rf "${TMP_DIR}"
