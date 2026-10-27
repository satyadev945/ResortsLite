#!/bin/bash
set -e
set -o pipefail

# ============================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# Usage: ./scripts/deploy-image.sh
# Run from repository root directory
# ============================================================

APP_NAME="resortslite"
NAMESPACE="resortslite"
K8S_DIR="kubernetes"

echo "=============================================="
echo "  ResortsLite — AWS EKS Deployment Script"
echo "=============================================="
echo ""

# ── AWS / EKS configuration ──────────────────────────────────
read -rp "Enter AWS region [us-east-1]: " AWS_REGION
AWS_REGION="${AWS_REGION:-us-east-1}"

read -rp "Enter EKS cluster name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: EKS cluster name is required." >&2
  exit 1
fi

# ── Docker image URI ─────────────────────────────────────────
read -rp "Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Docker image URI is required." >&2
  exit 1
fi

echo ""
echo "── Application Environment Variables ─────────────────"
echo "  (Press Enter to skip optional values)"
echo ""

# Oracle Database
read -rp "Enter SPRING_DATASOURCE_URL (e.g. jdbc:oracle:thin:@<host>:1521:<sid>): " SPRING_DATASOURCE_URL
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:oracle:thin:@db-host:1521:ORCL}"

# Redis / ElastiCache
read -rp "Enter REDIS_HOST (ElastiCache endpoint): " REDIS_HOST
REDIS_HOST="${REDIS_HOST:-localhost}"

read -rp "Enter REDIS_PORT [6379]: " REDIS_PORT
REDIS_PORT="${REDIS_PORT:-6379}"

read -rp "Enter REDIS_SSL [false]: " REDIS_SSL
REDIS_SSL="${REDIS_SSL:-false}"

# Internal service endpoints
read -rp "Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: " PAYMENT_API_URL
PAYMENT_API_URL="${PAYMENT_API_URL:-http://payment-service:9090/payments/charge}"

read -rp "Enter APP_INVENTORY_ENDPOINT [http://inventory-service:8081/rooms]: " APP_INVENTORY_ENDPOINT
APP_INVENTORY_ENDPOINT="${APP_INVENTORY_ENDPOINT:-http://inventory-service:8081/rooms}"

read -rp "Enter APP_NOTIFICATION_ENDPOINT [http://notify-service:7070/send]: " APP_NOTIFICATION_ENDPOINT
APP_NOTIFICATION_ENDPOINT="${APP_NOTIFICATION_ENDPOINT:-http://notify-service:7070/send}"

echo ""
echo "── Configuring kubectl for EKS ────────────────────────"
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"
if [ $? -ne 0 ]; then
  echo "ERROR: Failed to configure kubectl for EKS cluster '$CLUSTER_NAME'." >&2
  exit 1
fi

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster." >&2; exit 1; }
echo ""

# ── Substitute placeholders in manifests ─────────────────────
echo "── Updating Kubernetes manifests ──────────────────────"

# Work on copies to avoid modifying originals
cp -r "$K8S_DIR" /tmp/resortslite-k8s-deploy

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                                   /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{SPRING_DATASOURCE_URL}}|${SPRING_DATASOURCE_URL}|g"           /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST}|g"                                 /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT}|g"                                 /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{REDIS_SSL}}|${REDIS_SSL}|g"                                   /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL}|g"                       /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{APP_INVENTORY_ENDPOINT}}|${APP_INVENTORY_ENDPOINT}|g"         /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{APP_NOTIFICATION_ENDPOINT}}|${APP_NOTIFICATION_ENDPOINT}|g"   /tmp/resortslite-k8s-deploy/deployment.yaml

echo "Manifests updated."
echo ""

# ── Apply manifests in order ─────────────────────────────────
echo "── Applying Kubernetes manifests ──────────────────────"

echo "[1/4] Applying namespace..."
kubectl apply -f /tmp/resortslite-k8s-deploy/namespace.yaml

echo "[2/4] Applying deployment..."
kubectl apply -f /tmp/resortslite-k8s-deploy/deployment.yaml

echo "[3/4] Applying service..."
kubectl apply -f /tmp/resortslite-k8s-deploy/service.yaml

echo "[4/4] Applying ingress..."
kubectl apply -f /tmp/resortslite-k8s-deploy/ingress.yaml

echo ""

# ── Wait for rollout ─────────────────────────────────────────
echo "── Waiting for deployment rollout ─────────────────────"
kubectl rollout status deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=300s
if [ $? -ne 0 ]; then
  echo ""
  echo "ERROR: Deployment rollout failed. Initiating rollback..." >&2
  kubectl rollout undo deployment/"$APP_NAME" -n "$NAMESPACE"
  echo "Rollback initiated. Check pod logs:"
  echo "  kubectl logs -l app=$APP_NAME -n $NAMESPACE --tail=50"
  exit 1
fi

echo ""

# ── Verify resources ─────────────────────────────────────────
echo "── Verifying deployed resources ───────────────────────"
kubectl get pods,svc,ingress -n "$NAMESPACE"
echo ""

# ── Display application URL ──────────────────────────────────
echo "── Application Access ──────────────────────────────────"
INGRESS_HOST=$(kubectl get ingress resortslite-ingress -n "$NAMESPACE" \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "pending")

if [ "$INGRESS_HOST" != "pending" ] && [ -n "$INGRESS_HOST" ]; then
  echo "  Application URL : http://$INGRESS_HOST"
  echo "  Health check    : http://$INGRESS_HOST/actuator/health"
else
  echo "  Ingress hostname is still provisioning. Run:"
  echo "  kubectl get ingress -n $NAMESPACE"
fi

# Cleanup temp files
rm -rf /tmp/resortslite-k8s-deploy

echo ""
echo "=============================================="
echo "  Deployment completed successfully!"
echo "=============================================="
echo ""
echo "Useful commands:"
echo "  kubectl get pods -n $NAMESPACE"
echo "  kubectl logs -l app=$APP_NAME -n $NAMESPACE --tail=100"
echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE  # rollback"
