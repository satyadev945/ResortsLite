#!/bin/bash
set -e
set -o pipefail

# =============================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# =============================================================

APP_NAME="resortslite"
NAMESPACE="resortslite"

echo "=============================================="
echo "  ResortsLite — AWS EKS Deployment"
echo "=============================================="
echo ""

# Prompt for AWS region
read -rp "Enter AWS Region [us-east-1]: " AWS_REGION
AWS_REGION="${AWS_REGION:-us-east-1}"

# Prompt for EKS cluster name
read -rp "Enter EKS Cluster Name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: EKS Cluster Name is required." >&2
  exit 1
fi

# Prompt for Docker image URI
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
read -rp "Enter REDIS_HOST (ElastiCache endpoint): " REDIS_HOST
read -rp "Enter REDIS_PORT [6379]: " REDIS_PORT
REDIS_PORT="${REDIS_PORT:-6379}"
read -rp "Enter REDIS_SSL [false]: " REDIS_SSL
REDIS_SSL="${REDIS_SSL:-false}"
read -rp "Enter PAYMENT_API_URL: " PAYMENT_API_URL
read -rp "Enter APP_PAYMENT_ENDPOINT: " APP_PAYMENT_ENDPOINT
read -rp "Enter APP_INVENTORY_ENDPOINT: " APP_INVENTORY_ENDPOINT
read -rp "Enter APP_NOTIFICATION_ENDPOINT: " APP_NOTIFICATION_ENDPOINT
read -rp "Enter REPORT_BASE_PATH [/var/reports]: " REPORT_BASE_PATH
REPORT_BASE_PATH="${REPORT_BASE_PATH:-/var/reports}"
read -rp "Enter BACKUP_PATH [/var/backups/nightly]: " BACKUP_PATH
BACKUP_PATH="${BACKUP_PATH:-/var/backups/nightly}"

echo ""
echo "Configuring kubectl for EKS cluster: $CLUSTER_NAME in $AWS_REGION ..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster." >&2; exit 1; }

echo ""
echo "Updating Kubernetes manifests with provided values..."

# Work on copies to avoid modifying originals
cp -r kubernetes kubernetes_deploy_tmp

# Replace IMAGE_URI placeholder
sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g" kubernetes_deploy_tmp/deployment.yaml

# Replace environment variable placeholders
[ -n "$SPRING_DATASOURCE_URL" ]       && sed -i "s|{{SPRING_DATASOURCE_URL}}|${SPRING_DATASOURCE_URL}|g"             kubernetes_deploy_tmp/deployment.yaml
[ -n "$REDIS_HOST" ]                  && sed -i "s|{{REDIS_HOST}}|${REDIS_HOST}|g"                                   kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT}|g"                                                                             kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REDIS_SSL}}|${REDIS_SSL}|g"                                                                               kubernetes_deploy_tmp/deployment.yaml
[ -n "$PAYMENT_API_URL" ]             && sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL}|g"                         kubernetes_deploy_tmp/deployment.yaml
[ -n "$APP_PAYMENT_ENDPOINT" ]        && sed -i "s|{{APP_PAYMENT_ENDPOINT}}|${APP_PAYMENT_ENDPOINT}|g"               kubernetes_deploy_tmp/deployment.yaml
[ -n "$APP_INVENTORY_ENDPOINT" ]      && sed -i "s|{{APP_INVENTORY_ENDPOINT}}|${APP_INVENTORY_ENDPOINT}|g"           kubernetes_deploy_tmp/deployment.yaml
[ -n "$APP_NOTIFICATION_ENDPOINT" ]   && sed -i "s|{{APP_NOTIFICATION_ENDPOINT}}|${APP_NOTIFICATION_ENDPOINT}|g"     kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REPORT_BASE_PATH}}|${REPORT_BASE_PATH}|g"                                                                 kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{BACKUP_PATH}}|${BACKUP_PATH}|g"                                                                           kubernetes_deploy_tmp/deployment.yaml

echo ""
echo "Applying Kubernetes manifests..."

echo "  [1/4] Applying namespace..."
kubectl apply -f kubernetes_deploy_tmp/namespace.yaml

echo "  [2/4] Applying deployment..."
kubectl apply -f kubernetes_deploy_tmp/deployment.yaml

echo "  [3/4] Applying service..."
kubectl apply -f kubernetes_deploy_tmp/service.yaml

echo "  [4/4] Applying ingress..."
kubectl apply -f kubernetes_deploy_tmp/ingress.yaml

echo ""
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/${APP_NAME} -n ${NAMESPACE} --timeout=300s

echo ""
echo "Verifying deployed resources..."
kubectl get pods,svc,ingress -n ${NAMESPACE}

echo ""
echo "Fetching application URL..."
INGRESS_HOST=$(kubectl get ingress ${APP_NAME}-ingress -n ${NAMESPACE} \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "<pending>")
echo "Application URL: http://${INGRESS_HOST}"

# Cleanup temp manifests
rm -rf kubernetes_deploy_tmp

echo ""
echo "=============================================="
echo "  Deployment Complete!"
echo "  Namespace : ${NAMESPACE}"
echo "  Image     : ${IMAGE_URI}"
echo "  URL       : http://${INGRESS_HOST}"
echo "=============================================="
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"
