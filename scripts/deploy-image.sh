#!/bin/bash
set -e
set -o pipefail

# =============================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# Usage: ./scripts/deploy-image.sh
# Run from the repository root directory
# =============================================================

APP_NAME="resortslite"
NAMESPACE="resortslite"
K8S_DIR="kubernetes"

echo "=============================================="
echo "  ResortsLite — AWS EKS Deployment"
echo "=============================================="

# ---- Collect deployment inputs ----
read -rp "Enter AWS Region (e.g. us-east-1): " AWS_REGION
if [ -z "$AWS_REGION" ]; then
  echo "ERROR: AWS Region is required." && exit 1
fi

read -rp "Enter EKS Cluster Name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: EKS Cluster Name is required." && exit 1
fi

read -rp "Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Docker image URI is required." && exit 1
fi

echo ""
echo "--- Application Environment Variables ---"
echo "Press Enter to skip any variable (placeholder will remain in manifest)."

read -rp "Enter REDIS_HOST (ElastiCache endpoint): " REDIS_HOST_VAL
read -rp "Enter REDIS_PORT [6379]: " REDIS_PORT_VAL
REDIS_PORT_VAL="${REDIS_PORT_VAL:-6379}"

read -rp "Enter SPRING_DATASOURCE_URL (Oracle JDBC URL): " SPRING_DATASOURCE_URL_VAL
read -rp "Enter SPRING_DATASOURCE_USERNAME: " SPRING_DATASOURCE_USERNAME_VAL
read -rsp "Enter SPRING_DATASOURCE_PASSWORD: " SPRING_DATASOURCE_PASSWORD_VAL
echo ""

read -rp "Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: " PAYMENT_API_URL_VAL
PAYMENT_API_URL_VAL="${PAYMENT_API_URL_VAL:-http://payment-service:9090/payments/charge}"

read -rp "Enter APP_PAYMENT_ENDPOINT [http://payment-svc.internal:9090/charge]: " APP_PAYMENT_ENDPOINT_VAL
APP_PAYMENT_ENDPOINT_VAL="${APP_PAYMENT_ENDPOINT_VAL:-http://payment-svc.internal:9090/charge}"

read -rp "Enter APP_INVENTORY_ENDPOINT [http://inventory-svc.internal:8081/rooms]: " APP_INVENTORY_ENDPOINT_VAL
APP_INVENTORY_ENDPOINT_VAL="${APP_INVENTORY_ENDPOINT_VAL:-http://inventory-svc.internal:8081/rooms}"

read -rp "Enter APP_NOTIFICATION_ENDPOINT [http://notify.internal:7070/send]: " APP_NOTIFICATION_ENDPOINT_VAL
APP_NOTIFICATION_ENDPOINT_VAL="${APP_NOTIFICATION_ENDPOINT_VAL:-http://notify.internal:7070/send}"

read -rp "Enter REPORT_BASE_PATH [/var/reports]: " REPORT_BASE_PATH_VAL
REPORT_BASE_PATH_VAL="${REPORT_BASE_PATH_VAL:-/var/reports}"

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
TMP_DIR=$(mktemp -d)
cp -r "${K8S_DIR}/." "${TMP_DIR}/"

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                                       "${TMP_DIR}/deployment.yaml"
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST_VAL}|g"                                 "${TMP_DIR}/deployment.yaml"
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT_VAL}|g"                                 "${TMP_DIR}/deployment.yaml"
sed -i "s|{{SPRING_DATASOURCE_URL}}|${SPRING_DATASOURCE_URL_VAL}|g"           "${TMP_DIR}/deployment.yaml"
sed -i "s|{{SPRING_DATASOURCE_USERNAME}}|${SPRING_DATASOURCE_USERNAME_VAL}|g" "${TMP_DIR}/deployment.yaml"
sed -i "s|{{SPRING_DATASOURCE_PASSWORD}}|${SPRING_DATASOURCE_PASSWORD_VAL}|g" "${TMP_DIR}/deployment.yaml"
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL_VAL}|g"                       "${TMP_DIR}/deployment.yaml"
sed -i "s|{{APP_PAYMENT_ENDPOINT}}|${APP_PAYMENT_ENDPOINT_VAL}|g"             "${TMP_DIR}/deployment.yaml"
sed -i "s|{{APP_INVENTORY_ENDPOINT}}|${APP_INVENTORY_ENDPOINT_VAL}|g"         "${TMP_DIR}/deployment.yaml"
sed -i "s|{{APP_NOTIFICATION_ENDPOINT}}|${APP_NOTIFICATION_ENDPOINT_VAL}|g"   "${TMP_DIR}/deployment.yaml"
sed -i "s|{{REPORT_BASE_PATH}}|${REPORT_BASE_PATH_VAL}|g"                     "${TMP_DIR}/deployment.yaml"

# ---- Apply manifests ----
echo ""
echo "Applying Kubernetes manifests..."

echo "  [1/4] Applying namespace..."
kubectl apply -f "${TMP_DIR}/namespace.yaml"

echo "  [2/4] Applying deployment..."
kubectl apply -f "${TMP_DIR}/deployment.yaml"

echo "  [3/4] Applying service..."
kubectl apply -f "${TMP_DIR}/service.yaml"

echo "  [4/4] Applying ingress..."
kubectl apply -f "${TMP_DIR}/ingress.yaml"

# ---- Wait for rollout ----
echo ""
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/${APP_NAME} -n ${NAMESPACE} --timeout=300s

# ---- Verify resources ----
echo ""
echo "Verifying deployed resources..."
kubectl get pods,svc,ingress -n ${NAMESPACE}

# ---- Display access URL ----
echo ""
INGRESS_HOST=$(kubectl get ingress ${APP_NAME}-ingress -n ${NAMESPACE} \
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
