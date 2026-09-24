#!/bin/bash
# =============================================================================
# deploy-image.sh  –  Deploy ResortsLite to GCP GKE
# Usage: bash scripts/deploy-image.sh
# Run from the repository root directory.
# =============================================================================
set -e
set -o pipefail

APP_NAME="resortslite"
NAMESPACE="resortslite"

echo "=============================================="
echo "  ResortsLite – GKE Deployment"
echo "=============================================="

# ---------- GCP / GKE credentials ----------
echo ""
read -rp "GCP Project ID: " GCP_PROJECT
read -rp "GCP Zone (e.g. us-central1-a): " GCP_ZONE
read -rp "GKE Cluster name: " CLUSTER_NAME

# ---------- Image URI ----------
echo ""
read -rp "Full Docker image URI (e.g. us-central1-docker.pkg.dev/my-project/repo/resortslite:1.0.0): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Image URI cannot be empty."
  exit 1
fi

# ---------- Application environment variables ----------
echo ""
echo "--- Application Environment Variables ---"
echo "(Press Enter to keep the placeholder / skip)"

read -rp "REDIS_HOST (Google Cloud Memorystore IP/hostname): " REDIS_HOST_VAL
if [ -z "$REDIS_HOST_VAL" ]; then REDIS_HOST_VAL="localhost"; fi

read -rp "REDIS_PORT [6379]: " REDIS_PORT_VAL
if [ -z "$REDIS_PORT_VAL" ]; then REDIS_PORT_VAL="6379"; fi

read -rsp "REDIS_PASSWORD (leave blank if auth disabled): " REDIS_PASSWORD_VAL
echo ""

read -rp "PAYMENT_API_URL [http://payment-svc.payments.svc.cluster.local:9090/payments/charge]: " PAYMENT_API_URL_VAL
if [ -z "$PAYMENT_API_URL_VAL" ]; then PAYMENT_API_URL_VAL="http://payment-svc.payments.svc.cluster.local:9090/payments/charge"; fi

read -rp "REPORT_BASE_PATH [/var/legacy/reports/]: " REPORT_BASE_PATH_VAL
if [ -z "$REPORT_BASE_PATH_VAL" ]; then REPORT_BASE_PATH_VAL="/var/legacy/reports/"; fi

# ---------- Configure kubectl ----------
echo ""
echo "Configuring kubectl for cluster: $CLUSTER_NAME ..."
gcloud container clusters get-credentials "$CLUSTER_NAME" \
  --zone "$GCP_ZONE" \
  --project "$GCP_PROJECT"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to cluster."; exit 1; }

# ---------- Patch manifests ----------
echo ""
echo "Patching Kubernetes manifests..."

# Work on copies so originals stay clean
cp kubernetes/deployment.yaml /tmp/deployment_patched.yaml

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                   /tmp/deployment_patched.yaml
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST_VAL}|g"             /tmp/deployment_patched.yaml
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT_VAL}|g"             /tmp/deployment_patched.yaml
sed -i "s|{{REDIS_PASSWORD}}|${REDIS_PASSWORD_VAL}|g"     /tmp/deployment_patched.yaml
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL_VAL}|g"   /tmp/deployment_patched.yaml
sed -i "s|{{REPORT_BASE_PATH}}|${REPORT_BASE_PATH_VAL}|g" /tmp/deployment_patched.yaml

# ---------- Apply manifests ----------
echo ""
echo "Applying Kubernetes manifests..."

echo "  [1/4] Namespace..."
kubectl apply -f kubernetes/namespace.yaml

echo "  [2/4] Deployment..."
kubectl apply -f /tmp/deployment_patched.yaml

echo "  [3/4] Service..."
kubectl apply -f kubernetes/service.yaml

echo "  [4/4] Ingress..."
kubectl apply -f kubernetes/ingress.yaml

# ---------- Wait for rollout ----------
echo ""
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=300s

# ---------- Verify ----------
echo ""
echo "Deployed resources:"
kubectl get pods,svc,ingress -n "$NAMESPACE"

# ---------- Display URL ----------
echo ""
INGRESS_IP=$(kubectl get ingress resortslite-ingress -n "$NAMESPACE" \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "pending")

if [ "$INGRESS_IP" = "pending" ] || [ -z "$INGRESS_IP" ]; then
  echo "Ingress IP is still being provisioned. Run:"
  echo "  kubectl get ingress -n $NAMESPACE"
else
  echo "Application URL: http://$INGRESS_IP"
fi

echo ""
echo "=============================================="
echo "  Deployment complete!"
echo "=============================================="
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
