#!/bin/bash
set -e
set -o pipefail

# ============================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# ============================================================

APP_NAME="resortslite"
NAMESPACE="resortslite"
K8S_DIR="kubernetes"

echo "=============================================="
echo "  ResortsLite — AWS EKS Deployment Script"
echo "=============================================="
echo ""

# ── AWS / EKS configuration ────────────────────────────────
read -rp "Enter AWS Region (e.g. us-east-1): " AWS_REGION
if [ -z "$AWS_REGION" ]; then
  echo "ERROR: AWS Region is required." >&2
  exit 1
fi

read -rp "Enter EKS Cluster Name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: EKS Cluster Name is required." >&2
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
echo ""

read -rp "Enter SPRING_DATASOURCE_URL (Oracle JDBC URL): " SPRING_DATASOURCE_URL
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:oracle:thin:@localhost:1521:ORCL}"

read -rp "Enter SPRING_DATASOURCE_USERNAME: " SPRING_DATASOURCE_USERNAME
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

read -rp "Enter REDIS_SSL (true/false) [false]: " REDIS_SSL
REDIS_SSL="${REDIS_SSL:-false}"

read -rp "Enter PAYMENT_API_URL: " PAYMENT_API_URL
PAYMENT_API_URL="${PAYMENT_API_URL:-http://payment-service:9090/payments/charge}"

read -rp "Enter REPORT_BASE_PATH [/var/reports]: " REPORT_BASE_PATH
REPORT_BASE_PATH="${REPORT_BASE_PATH:-/var/reports}"

# ── Configure kubectl for EKS ──────────────────────────────
echo ""
echo "Configuring kubectl for EKS cluster '${CLUSTER_NAME}' in '${AWS_REGION}'..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"
echo "kubectl configured."

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster." >&2; exit 1; }

# ── Substitute placeholders in manifests ──────────────────
echo ""
echo "Updating Kubernetes manifests with deployment values..."

# Work on copies to avoid modifying originals
cp "${K8S_DIR}/deployment.yaml" "${K8S_DIR}/deployment.yaml.deploy"

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                                   "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{SPRING_DATASOURCE_URL}}|${SPRING_DATASOURCE_URL}|g"           "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{SPRING_DATASOURCE_USERNAME}}|${SPRING_DATASOURCE_USERNAME}|g" "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{SPRING_DATASOURCE_PASSWORD}}|${SPRING_DATASOURCE_PASSWORD}|g" "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST}|g"                                 "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT}|g"                                 "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{REDIS_PASSWORD}}|${REDIS_PASSWORD}|g"                         "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{REDIS_SSL}}|${REDIS_SSL}|g"                                   "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL}|g"                       "${K8S_DIR}/deployment.yaml.deploy"
sed -i "s|{{REPORT_BASE_PATH}}|${REPORT_BASE_PATH}|g"                     "${K8S_DIR}/deployment.yaml.deploy"

echo "Manifests updated."

# ── Apply Kubernetes manifests ─────────────────────────────
echo ""
echo "Applying Kubernetes manifests..."

echo "  [1/4] Applying namespace..."
kubectl apply -f "${K8S_DIR}/namespace.yaml"

echo "  [2/4] Applying deployment..."
kubectl apply -f "${K8S_DIR}/deployment.yaml.deploy"

echo "  [3/4] Applying service..."
kubectl apply -f "${K8S_DIR}/service.yaml"

echo "  [4/4] Applying ingress..."
kubectl apply -f "${K8S_DIR}/ingress.yaml"

# ── Clean up temporary file ────────────────────────────────
rm -f "${K8S_DIR}/deployment.yaml.deploy"

# ── Wait for rollout ───────────────────────────────────────
echo ""
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/"${APP_NAME}" -n "${NAMESPACE}" --timeout=300s

# ── Verify resources ───────────────────────────────────────
echo ""
echo "Verifying deployed resources..."
kubectl get pods,svc,ingress -n "${NAMESPACE}"

# ── Display access URL ─────────────────────────────────────
echo ""
echo "Fetching application ingress URL..."
INGRESS_HOST=$(kubectl get ingress "${APP_NAME}-ingress" -n "${NAMESPACE}" \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "pending")

echo ""
echo "=============================================="
echo "  Deployment Complete!"
echo "  Namespace : ${NAMESPACE}"
echo "  Image     : ${IMAGE_URI}"
if [ "$INGRESS_HOST" != "pending" ] && [ -n "$INGRESS_HOST" ]; then
  echo "  App URL   : http://${INGRESS_HOST}"
else
  echo "  App URL   : (ALB provisioning — check 'kubectl get ingress -n ${NAMESPACE}' shortly)"
fi
echo ""
echo "  Rollback command:"
echo "    kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"
echo "=============================================="
