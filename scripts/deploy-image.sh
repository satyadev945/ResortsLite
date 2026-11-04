#!/bin/bash
# =============================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# =============================================================
set -e
set -o pipefail

APP_NAME="resortslite"
NAMESPACE="resortslite"
K8S_DIR="kubernetes"

echo "=============================================="
echo "  ResortsLite — AWS EKS Deployment Script"
echo "=============================================="
echo ""

# ── AWS / EKS configuration ────────────────────────────────────
read -rp "Enter AWS Region [us-east-1]: " AWS_REGION
AWS_REGION="${AWS_REGION:-us-east-1}"

read -rp "Enter EKS Cluster Name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: EKS Cluster Name is required." >&2
  exit 1
fi

# ── Docker image URI ───────────────────────────────────────────
read -rp "Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Docker image URI is required." >&2
  exit 1
fi

# ── Application environment variables ─────────────────────────
echo ""
echo "--- Application Environment Variables ---"
echo "Press Enter to skip any variable (placeholder will remain in manifest)."
echo ""

read -rp "Enter SPRING_DATASOURCE_URL (Oracle JDBC URL): " SPRING_DATASOURCE_URL
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:oracle:thin:@<DB_HOST>:1521:<DB_SID>}"

read -rp "Enter SPRING_DATASOURCE_USERNAME [admin]: " SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-admin}"

read -rsp "Enter SPRING_DATASOURCE_PASSWORD: " SPRING_DATASOURCE_PASSWORD
echo ""
SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-changeme}"

read -rp "Enter REDIS_HOST (ElastiCache endpoint): " REDIS_HOST
REDIS_HOST="${REDIS_HOST:-localhost}"

read -rp "Enter REDIS_PORT [6379]: " REDIS_PORT
REDIS_PORT="${REDIS_PORT:-6379}"

read -rsp "Enter REDIS_PASSWORD (leave blank if none): " REDIS_PASSWORD
echo ""

read -rp "Enter REDIS_SSL [false]: " REDIS_SSL
REDIS_SSL="${REDIS_SSL:-false}"

read -rp "Enter BOOKING_CACHE_TTL_SECONDS [3600]: " BOOKING_CACHE_TTL_SECONDS
BOOKING_CACHE_TTL_SECONDS="${BOOKING_CACHE_TTL_SECONDS:-3600}"

read -rp "Enter PAYMENT_API_URL: " PAYMENT_API_URL
PAYMENT_API_URL="${PAYMENT_API_URL:-http://payment-svc.default.svc.cluster.local:9090/payments/charge}"

read -rp "Enter APP_INVENTORY_ENDPOINT: " APP_INVENTORY_ENDPOINT
APP_INVENTORY_ENDPOINT="${APP_INVENTORY_ENDPOINT:-http://inventory-svc.internal:8081/rooms}"

read -rp "Enter APP_NOTIFICATION_ENDPOINT: " APP_NOTIFICATION_ENDPOINT
APP_NOTIFICATION_ENDPOINT="${APP_NOTIFICATION_ENDPOINT:-http://notify.internal:7070/send}"

read -rp "Enter REPORTS_BASE_PATH [/var/reports]: " REPORTS_BASE_PATH
REPORTS_BASE_PATH="${REPORTS_BASE_PATH:-/var/reports}"

read -rp "Enter BACKUP_PATH [/var/backups/resort/nightly]: " BACKUP_PATH
BACKUP_PATH="${BACKUP_PATH:-/var/backups/resort/nightly}"

# ── Configure kubectl for EKS ──────────────────────────────────
echo ""
echo "Configuring kubectl for EKS cluster '${CLUSTER_NAME}' in '${AWS_REGION}'..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster." >&2; exit 1; }

# ── Substitute placeholders in manifests ──────────────────────
echo ""
echo "Updating Kubernetes manifests with deployment values..."

# Work on copies to avoid modifying originals
cp -r "$K8S_DIR" /tmp/k8s-deploy-resortslite

DEPLOY_FILE="/tmp/k8s-deploy-resortslite/deployment.yaml"

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                                   "$DEPLOY_FILE"
sed -i "s|{{SPRING_DATASOURCE_URL}}|${SPRING_DATASOURCE_URL}|g"           "$DEPLOY_FILE"
sed -i "s|{{SPRING_DATASOURCE_USERNAME}}|${SPRING_DATASOURCE_USERNAME}|g" "$DEPLOY_FILE"
sed -i "s|{{SPRING_DATASOURCE_PASSWORD}}|${SPRING_DATASOURCE_PASSWORD}|g" "$DEPLOY_FILE"
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST}|g"                                 "$DEPLOY_FILE"
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT}|g"                                 "$DEPLOY_FILE"
sed -i "s|{{REDIS_PASSWORD}}|${REDIS_PASSWORD}|g"                         "$DEPLOY_FILE"
sed -i "s|{{REDIS_SSL}}|${REDIS_SSL}|g"                                   "$DEPLOY_FILE"
sed -i "s|{{BOOKING_CACHE_TTL_SECONDS}}|${BOOKING_CACHE_TTL_SECONDS}|g"   "$DEPLOY_FILE"
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL}|g"                       "$DEPLOY_FILE"
sed -i "s|{{APP_INVENTORY_ENDPOINT}}|${APP_INVENTORY_ENDPOINT}|g"         "$DEPLOY_FILE"
sed -i "s|{{APP_NOTIFICATION_ENDPOINT}}|${APP_NOTIFICATION_ENDPOINT}|g"   "$DEPLOY_FILE"
sed -i "s|{{REPORTS_BASE_PATH}}|${REPORTS_BASE_PATH}|g"                   "$DEPLOY_FILE"
sed -i "s|{{BACKUP_PATH}}|${BACKUP_PATH}|g"                               "$DEPLOY_FILE"

echo "Manifests updated."

# ── Apply Kubernetes manifests ─────────────────────────────────
echo ""
echo "Applying Kubernetes manifests..."

echo "[1/4] Applying namespace..."
kubectl apply -f /tmp/k8s-deploy-resortslite/namespace.yaml

echo "[2/4] Applying deployment..."
kubectl apply -f "$DEPLOY_FILE"

echo "[3/4] Applying service..."
kubectl apply -f /tmp/k8s-deploy-resortslite/service.yaml

echo "[4/4] Applying ingress..."
kubectl apply -f /tmp/k8s-deploy-resortslite/ingress.yaml

# ── Wait for rollout ───────────────────────────────────────────
echo ""
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/"${APP_NAME}" -n "${NAMESPACE}" --timeout=300s || {
  echo ""
  echo "ERROR: Deployment rollout timed out or failed."
  echo "To rollback, run: kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"
  exit 1
}

# ── Verify resources ───────────────────────────────────────────
echo ""
echo "Verifying deployed resources..."
kubectl get pods,svc,ingress -n "${NAMESPACE}"

# ── Display access URL ─────────────────────────────────────────
echo ""
echo "Fetching application URL from ingress..."
INGRESS_HOST=$(kubectl get ingress resortslite-ingress -n "${NAMESPACE}" \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "<pending>")

echo ""
echo "=============================================="
echo "  Deployment Complete!"
echo "  Application URL: http://${INGRESS_HOST}"
echo "  Health Check:    http://${INGRESS_HOST}/actuator/health"
echo ""
echo "  Rollback command (if needed):"
echo "  kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"
echo "=============================================="

# Cleanup temp files
rm -rf /tmp/k8s-deploy-resortslite
