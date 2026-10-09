#!/bin/bash
set -e
set -o pipefail

# ============================================================
# deploy-image.sh — Deploy Gitpul Resort MonoCMP to AWS EKS
# ============================================================

APP_NAME="gitpul-resort-monocmp"
NAMESPACE="gitpul-resort-monocmp"
K8S_DIR="kubernetes"

echo "=============================================="
echo "  Gitpul Resort MonoCMP — Deploy to AWS EKS"
echo "=============================================="

# ---- Prompt for AWS / EKS details ------------------------
echo ""
read -rp "Enter AWS Region (e.g. us-east-1): " AWS_REGION
if [ -z "$AWS_REGION" ]; then
  echo "ERROR: AWS Region is required."
  exit 1
fi

read -rp "Enter EKS Cluster Name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: EKS Cluster Name is required."
  exit 1
fi

read -rp "Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/gitpul-resort-monocmp:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Docker image URI is required."
  exit 1
fi

# ---- Prompt for application environment variables --------
echo ""
echo "--- Application Environment Variables ---"
echo "(Press Enter to keep placeholder / use default)"

read -rp "Enter REDIS_HOST (ElastiCache endpoint) [localhost]: " REDIS_HOST_VAL
REDIS_HOST_VAL="${REDIS_HOST_VAL:-localhost}"

read -rp "Enter REDIS_PORT [6379]: " REDIS_PORT_VAL
REDIS_PORT_VAL="${REDIS_PORT_VAL:-6379}"

read -rp "Enter REDIS_SSL (true/false) [false]: " REDIS_SSL_VAL
REDIS_SSL_VAL="${REDIS_SSL_VAL:-false}"

read -rp "Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: " PAYMENT_API_URL_VAL
PAYMENT_API_URL_VAL="${PAYMENT_API_URL_VAL:-http://payment-service:9090/payments/charge}"

read -rp "Enter BOOKING_CACHE_TTL_SECONDS [3600]: " BOOKING_CACHE_TTL_VAL
BOOKING_CACHE_TTL_VAL="${BOOKING_CACHE_TTL_VAL:-3600}"

# ---- Configure kubectl for EKS ---------------------------
echo ""
echo "Configuring kubectl for EKS cluster: $CLUSTER_NAME in $AWS_REGION ..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster."; exit 1; }

# ---- Update Kubernetes manifests with actual values ------
echo ""
echo "Updating Kubernetes manifests with deployment values..."

# Use pipe delimiter to avoid conflicts with URLs containing slashes
sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                           "${K8S_DIR}/deployment.yaml"
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST_VAL}|g"                    "${K8S_DIR}/deployment.yaml"
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT_VAL}|g"                    "${K8S_DIR}/deployment.yaml"
sed -i "s|{{REDIS_SSL}}|${REDIS_SSL_VAL}|g"                      "${K8S_DIR}/deployment.yaml"
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL_VAL}|g"          "${K8S_DIR}/deployment.yaml"
sed -i "s|{{BOOKING_CACHE_TTL_SECONDS}}|${BOOKING_CACHE_TTL_VAL}|g" "${K8S_DIR}/deployment.yaml"

echo "Manifests updated."

# ---- Apply Kubernetes manifests in order -----------------
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

# ---- Wait for rollout ------------------------------------
echo ""
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/"${APP_NAME}" -n "${NAMESPACE}" --timeout=300s

# ---- Verify resources ------------------------------------
echo ""
echo "Verifying deployed resources..."
kubectl get pods,svc,ingress -n "${NAMESPACE}"

# ---- Display application URL -----------------------------
echo ""
echo "Fetching application ingress URL..."
INGRESS_HOST=$(kubectl get ingress "${APP_NAME}-ingress" -n "${NAMESPACE}" \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "pending")

echo ""
echo "=============================================="
echo "  Deployment Complete!"
echo "  Application: ${APP_NAME}"
echo "  Namespace:   ${NAMESPACE}"
echo "  Image:       ${IMAGE_URI}"
if [ "$INGRESS_HOST" != "pending" ] && [ -n "$INGRESS_HOST" ]; then
  echo "  URL:         http://${INGRESS_HOST}"
else
  echo "  URL:         (Ingress hostname pending — check 'kubectl get ingress -n ${NAMESPACE}')"
fi
echo "=============================================="
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"
