#!/bin/bash
set -e
set -o pipefail

# ============================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# ============================================================

APP_NAME="resortslite"
NAMESPACE="resortslite"
MANIFEST_DIR="kubernetes"

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

# ---- Application environment variables ----
echo ""
echo "--- Application Configuration (press Enter to skip optional values) ---"

read -rp "Enter SPRING_DATASOURCE_URL (e.g. jdbc:oracle:thin:@<host>:1521:<sid>): " SPRING_DATASOURCE_URL
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:oracle:thin:@db-host:1521:ORCL}"

read -rp "Enter SPRING_DATASOURCE_USERNAME [admin]: " SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-admin}"

read -rsp "Enter SPRING_DATASOURCE_PASSWORD: " SPRING_DATASOURCE_PASSWORD
echo ""
SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-changeme}"

read -rp "Enter REDIS_HOST (ElastiCache endpoint) [localhost]: " REDIS_HOST
REDIS_HOST="${REDIS_HOST:-localhost}"

read -rp "Enter REDIS_PORT [6379]: " REDIS_PORT
REDIS_PORT="${REDIS_PORT:-6379}"

read -rsp "Enter REDIS_PASSWORD (leave blank if none): " REDIS_PASSWORD
echo ""

read -rp "Enter PAYMENT_API_URL [http://payment-svc:9090/payments/charge]: " PAYMENT_API_URL
PAYMENT_API_URL="${PAYMENT_API_URL:-http://payment-svc:9090/payments/charge}"

# ---- Configure kubectl ----
echo ""
echo "Configuring kubectl for EKS cluster: ${CLUSTER_NAME} in ${AWS_REGION}..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster." >&2; exit 1; }

# ---- Substitute placeholders in manifests ----
echo ""
echo "Updating Kubernetes manifests with deployment values..."

# Work on copies to avoid modifying originals
cp -r "${MANIFEST_DIR}" /tmp/resortslite-k8s-deploy

sed -i 's|{{IMAGE_URI}}|'"${IMAGE_URI}"'|g'                                   /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i 's|{{SPRING_DATASOURCE_URL}}|'"${SPRING_DATASOURCE_URL}"'|g'           /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i 's|{{SPRING_DATASOURCE_USERNAME}}|'"${SPRING_DATASOURCE_USERNAME}"'|g' /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i 's|{{SPRING_DATASOURCE_PASSWORD}}|'"${SPRING_DATASOURCE_PASSWORD}"'|g' /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i 's|{{REDIS_HOST}}|'"${REDIS_HOST}"'|g'                                 /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i 's|{{REDIS_PORT}}|'"${REDIS_PORT}"'|g'                                 /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i 's|{{REDIS_PASSWORD}}|'"${REDIS_PASSWORD}"'|g'                         /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i 's|{{PAYMENT_API_URL}}|'"${PAYMENT_API_URL}"'|g'                       /tmp/resortslite-k8s-deploy/deployment.yaml

# ---- Apply manifests ----
echo ""
echo "Applying Kubernetes manifests..."

echo "  [1/4] Applying namespace..."
kubectl apply -f /tmp/resortslite-k8s-deploy/namespace.yaml

echo "  [2/4] Applying deployment..."
kubectl apply -f /tmp/resortslite-k8s-deploy/deployment.yaml

echo "  [3/4] Applying service..."
kubectl apply -f /tmp/resortslite-k8s-deploy/service.yaml

echo "  [4/4] Applying ingress..."
kubectl apply -f /tmp/resortslite-k8s-deploy/ingress.yaml

# ---- Wait for rollout ----
echo ""
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/"${APP_NAME}" -n "${NAMESPACE}" --timeout=300s || {
  echo "ERROR: Deployment rollout failed. Running rollback..."
  kubectl rollout undo deployment/"${APP_NAME}" -n "${NAMESPACE}"
  exit 1
}

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
echo "Useful commands:"
echo "  kubectl get pods -n ${NAMESPACE}"
echo "  kubectl logs -l app=${APP_NAME} -n ${NAMESPACE} --tail=100"
echo "  kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}  # rollback"

# Cleanup temp files
rm -rf /tmp/resortslite-k8s-deploy
