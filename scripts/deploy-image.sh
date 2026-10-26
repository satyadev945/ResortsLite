#!/bin/bash
# =============================================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# Usage: ./scripts/deploy-image.sh
# Run from the repository root directory.
# =============================================================================
set -e
set -o pipefail

APP_NAME="resortslite"
NAMESPACE="resortslite"

echo "=============================================="
echo "  ResortsLite — AWS EKS Deployment"
echo "=============================================="
echo ""

# ── Collect deployment inputs ─────────────────────────────────────────────────
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

read -rp "Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Docker image URI is required."
  exit 1
fi

echo ""
echo "--- Application Environment Variables ---"
echo "Press Enter to skip any variable (placeholder will remain in manifest)."
echo ""

read -rp "Enter SPRING_DATASOURCE_URL (e.g. jdbc:oracle:thin:@<host>:1521:<sid>): " SPRING_DATASOURCE_URL
read -rp "Enter REDIS_HOST (Amazon ElastiCache endpoint): " REDIS_HOST
read -rp "Enter REDIS_PORT (default 6379): " REDIS_PORT
read -rp "Enter REDIS_SSL (true/false, default false): " REDIS_SSL
read -rp "Enter PAYMENT_API_URL (e.g. https://payment-service/payments/charge): " PAYMENT_API_URL

# Apply defaults
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_SSL="${REDIS_SSL:-false}"

echo ""
echo "=============================================="
echo "  Configuring kubectl for EKS cluster..."
echo "=============================================="
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo ""
echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster."; exit 1; }

# ── Update Kubernetes manifests with collected values ─────────────────────────
echo ""
echo "Updating Kubernetes manifests..."

# Work on copies to avoid modifying originals permanently
cp -r kubernetes kubernetes_deploy_tmp

# Replace IMAGE_URI placeholder
sed -i 's|{{IMAGE_URI}}|'"$IMAGE_URI"'|g' kubernetes_deploy_tmp/deployment.yaml

# Replace datasource URL
if [ -n "$SPRING_DATASOURCE_URL" ]; then
  sed -i 's|{{SPRING_DATASOURCE_URL}}|'"$SPRING_DATASOURCE_URL"'|g' kubernetes_deploy_tmp/deployment.yaml
fi

# Replace Redis settings
if [ -n "$REDIS_HOST" ]; then
  sed -i 's|{{REDIS_HOST}}|'"$REDIS_HOST"'|g' kubernetes_deploy_tmp/deployment.yaml
fi
sed -i 's|{{REDIS_PORT}}|'"$REDIS_PORT"'|g' kubernetes_deploy_tmp/deployment.yaml
sed -i 's|{{REDIS_SSL}}|'"$REDIS_SSL"'|g' kubernetes_deploy_tmp/deployment.yaml

# Replace Payment API URL
if [ -n "$PAYMENT_API_URL" ]; then
  sed -i 's|{{PAYMENT_API_URL}}|'"$PAYMENT_API_URL"'|g' kubernetes_deploy_tmp/deployment.yaml
fi

# ── Apply manifests in order ──────────────────────────────────────────────────
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

# ── Wait for rollout ──────────────────────────────────────────────────────────
echo ""
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=300s || {
  echo ""
  echo "ERROR: Deployment rollout timed out or failed."
  echo "Run the following to investigate:"
  echo "  kubectl describe deployment/$APP_NAME -n $NAMESPACE"
  echo "  kubectl get pods -n $NAMESPACE"
  echo "  kubectl logs -l app=$APP_NAME -n $NAMESPACE --tail=50"
  echo ""
  echo "To rollback:"
  echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
  # Clean up temp directory before exit
  rm -rf kubernetes_deploy_tmp
  exit 1
}

# ── Verify resources ──────────────────────────────────────────────────────────
echo ""
echo "Verifying deployed resources..."
kubectl get pods,svc,ingress -n "$NAMESPACE"

# ── Display application URL ───────────────────────────────────────────────────
echo ""
INGRESS_HOST=$(kubectl get ingress resortslite-ingress -n "$NAMESPACE" \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "")

if [ -n "$INGRESS_HOST" ]; then
  echo "=============================================="
  echo "  Application is accessible at:"
  echo "  http://$INGRESS_HOST"
  echo "  https://$INGRESS_HOST"
  echo "  Health: http://$INGRESS_HOST/actuator/health"
  echo "=============================================="
else
  echo "Ingress hostname not yet assigned. Check again with:"
  echo "  kubectl get ingress -n $NAMESPACE"
fi

# ── Cleanup temp directory ────────────────────────────────────────────────────
rm -rf kubernetes_deploy_tmp

echo ""
echo "Deployment complete!"
