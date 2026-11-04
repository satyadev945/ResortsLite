#!/bin/bash
set -e
set -o pipefail

# =============================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# Usage: ./scripts/deploy-image.sh
# Run from repository root directory
# Prerequisites: aws-cli, kubectl
# =============================================================

APP_NAME="resortsLite"
NAMESPACE="resortsLite"
K8S_DIR="kubernetes"

echo "=============================================="
echo "  ResortsLite — Deploy to AWS EKS"
echo "=============================================="

# ---- Collect deployment inputs ----
read -rp "Enter AWS region (e.g. us-east-1): " AWS_REGION
if [ -z "$AWS_REGION" ]; then
  echo "ERROR: AWS region is required."
  exit 1
fi

read -rp "Enter EKS cluster name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: EKS cluster name is required."
  exit 1
fi

read -rp "Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortsLite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Docker image URI is required."
  exit 1
fi

echo ""
echo "--- Application Environment Variables ---"
echo "Press Enter to skip any variable (placeholder will remain in manifest)."

read -rp "Enter SPRING_DATASOURCE_URL (Oracle JDBC URL): " SPRING_DATASOURCE_URL
read -rp "Enter PAYMENT_API_URL [http://payment-service.default.svc.cluster.local:9090/payments/charge]: " PAYMENT_API_URL
PAYMENT_API_URL="${PAYMENT_API_URL:-http://payment-service.default.svc.cluster.local:9090/payments/charge}"
read -rp "Enter REPORT_BASE_PATH [/var/reports]: " REPORT_BASE_PATH
REPORT_BASE_PATH="${REPORT_BASE_PATH:-/var/reports}"
read -rp "Enter BACKUP_PATH [/var/backups/nightly]: " BACKUP_PATH
BACKUP_PATH="${BACKUP_PATH:-/var/backups/nightly}"

# ---- Configure kubectl for EKS ----
echo ""
echo "Configuring kubectl for EKS cluster: ${CLUSTER_NAME} in ${AWS_REGION}..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster."; exit 1; }

# ---- Substitute placeholders in manifests ----
echo ""
echo "Updating Kubernetes manifests with deployment values..."

# Work on copies to avoid modifying originals
cp "${K8S_DIR}/deployment.yaml" "${K8S_DIR}/deployment.yaml.bak"

sed -i 's|{{IMAGE_URI}}|'"${IMAGE_URI}"'|g' "${K8S_DIR}/deployment.yaml"
sed -i 's|{{SPRING_DATASOURCE_URL}}|'"${SPRING_DATASOURCE_URL}"'|g' "${K8S_DIR}/deployment.yaml"
sed -i 's|{{PAYMENT_API_URL}}|'"${PAYMENT_API_URL}"'|g' "${K8S_DIR}/deployment.yaml"
sed -i 's|{{REPORT_BASE_PATH}}|'"${REPORT_BASE_PATH}"'|g' "${K8S_DIR}/deployment.yaml"
sed -i 's|{{BACKUP_PATH}}|'"${BACKUP_PATH}"'|g' "${K8S_DIR}/deployment.yaml"

# ---- Apply manifests in order ----
echo ""
echo "Applying Kubernetes manifests..."

echo "  [1/4] Applying namespace..."
kubectl apply -f "${K8S_DIR}/namespace.yaml"

echo "  [2/4] Applying deployment..."
kubectl apply -f "${K8S_DIR}/deployment.yaml"

echo "  [3/4] Applying service..."
kubectl apply -f "${K8S_DIR}/service.yaml"

echo "  [4/4] Applying ingress..."
kubectl apply -f "${K8S_DIR}/ingress.yaml"

# ---- Restore original deployment.yaml ----
mv "${K8S_DIR}/deployment.yaml.bak" "${K8S_DIR}/deployment.yaml"

# ---- Wait for rollout ----
echo ""
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/"${APP_NAME}" -n "${NAMESPACE}" --timeout=300s

# ---- Verify resources ----
echo ""
echo "Verifying deployed resources..."
kubectl get pods,svc,ingress -n "${NAMESPACE}"

# ---- Display access URL ----
echo ""
echo "Fetching application ingress URL..."
INGRESS_HOST=$(kubectl get ingress "${APP_NAME}-ingress" -n "${NAMESPACE}" \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "<pending>")

echo ""
echo "=============================================="
echo "  Deployment Complete!"
echo "  Application URL: http://${INGRESS_HOST}"
echo "  Namespace:       ${NAMESPACE}"
echo "  Image:           ${IMAGE_URI}"
echo "=============================================="
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"
