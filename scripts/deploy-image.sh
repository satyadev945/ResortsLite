#!/bin/bash
set -e
set -o pipefail

# ============================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# Usage: ./scripts/deploy-image.sh
# Run from repository root directory
# Prerequisites: aws-cli, kubectl
# ============================================================

echo "=============================================="
echo "  ResortsLite — Deploy to AWS EKS"
echo "=============================================="
echo ""

# ---- AWS / EKS configuration ----
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

read -rp "Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Docker image URI is required."
  exit 1
fi

echo ""
echo "---- Application Environment Variables ----"
echo "Press Enter to skip any variable (placeholder will remain in manifest)."
echo ""

read -rp "Enter REDIS_HOST (ElastiCache primary endpoint) [localhost]: " REDIS_HOST_VAL
REDIS_HOST_VAL="${REDIS_HOST_VAL:-localhost}"

read -rp "Enter REDIS_PORT [6379]: " REDIS_PORT_VAL
REDIS_PORT_VAL="${REDIS_PORT_VAL:-6379}"

read -rp "Enter PAYMENT_API_URL [http://payment-svc.payments.svc.cluster.local:9090/payments/charge]: " PAYMENT_API_URL_VAL
PAYMENT_API_URL_VAL="${PAYMENT_API_URL_VAL:-http://payment-svc.payments.svc.cluster.local:9090/payments/charge}"

read -rp "Enter REPORT_BASE_PATH [/var/reports]: " REPORT_BASE_PATH_VAL
REPORT_BASE_PATH_VAL="${REPORT_BASE_PATH_VAL:-/var/reports}"

read -rp "Enter BACKUP_PATH [/var/backups/resorts]: " BACKUP_PATH_VAL
BACKUP_PATH_VAL="${BACKUP_PATH_VAL:-/var/backups/resorts}"

echo ""
echo "Configuring kubectl for EKS cluster: $CLUSTER_NAME in $AWS_REGION ..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster."; exit 1; }

echo ""
echo "Substituting placeholders in Kubernetes manifests..."

# Work on copies to avoid modifying originals
cp -r kubernetes kubernetes_deploy_tmp

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                         kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST_VAL}|g"                   kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT_VAL}|g"                   kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL_VAL}|g"         kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REPORT_BASE_PATH}}|${REPORT_BASE_PATH_VAL}|g"       kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{BACKUP_PATH}}|${BACKUP_PATH_VAL}|g"                 kubernetes_deploy_tmp/deployment.yaml

echo ""
echo "Applying Kubernetes manifests..."
kubectl apply -f kubernetes_deploy_tmp/namespace.yaml
kubectl apply -f kubernetes_deploy_tmp/deployment.yaml
kubectl apply -f kubernetes_deploy_tmp/service.yaml
kubectl apply -f kubernetes_deploy_tmp/ingress.yaml

echo ""
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/resortslite -n resortslite --timeout=300s

echo ""
echo "Verifying deployed resources..."
kubectl get pods,svc,ingress -n resortslite

echo ""
echo "Fetching application URL from Ingress..."
INGRESS_HOST=$(kubectl get ingress resortslite-ingress -n resortslite \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "pending")
echo "Application URL: http://${INGRESS_HOST}"

# Clean up temporary manifests
rm -rf kubernetes_deploy_tmp

echo ""
echo "=============================================="
echo "  Deployment Complete!"
echo "  Namespace : resortslite"
echo "  Image     : $IMAGE_URI"
echo "  Cluster   : $CLUSTER_NAME ($AWS_REGION)"
echo "=============================================="
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/resortslite -n resortslite"
