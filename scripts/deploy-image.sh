#!/bin/bash
set -e
set -o pipefail

# ============================================================
# deploy-image.sh — Deploy ResortsLite to GCP GKE
# ============================================================

echo "=============================================="
echo "  ResortsLite — GKE Deployment"
echo "=============================================="
echo ""

# ---- GCP / GKE configuration ----
read -rp "Enter GCP Project ID: " GCP_PROJECT
if [ -z "$GCP_PROJECT" ]; then
  echo "❌ GCP Project ID is required. Exiting."
  exit 1
fi

read -rp "Enter GCP Zone (e.g. us-central1-a): " GCP_ZONE
if [ -z "$GCP_ZONE" ]; then
  echo "❌ GCP Zone is required. Exiting."
  exit 1
fi

read -rp "Enter GKE Cluster Name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "❌ GKE Cluster Name is required. Exiting."
  exit 1
fi

read -rp "Enter full Docker image URI (e.g. us-central1-docker.pkg.dev/my-project/my-repo/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "❌ Docker image URI is required. Exiting."
  exit 1
fi

echo ""
echo "---- Application Environment Variables ----"
echo "Press Enter to keep the default value shown in brackets."
echo ""

read -rp "Enter REDIS_HOST (default: redis-service): " REDIS_HOST
REDIS_HOST="${REDIS_HOST:-redis-service}"

read -rp "Enter REDIS_PORT (default: 6379): " REDIS_PORT
REDIS_PORT="${REDIS_PORT:-6379}"

read -rp "Enter GCP_PROJECT_ID for Pub/Sub (default: $GCP_PROJECT): " PUBSUB_GCP_PROJECT
PUBSUB_GCP_PROJECT="${PUBSUB_GCP_PROJECT:-$GCP_PROJECT}"

read -rp "Enter PUBSUB_BOOKING_TOPIC (default: booking-events): " PUBSUB_BOOKING_TOPIC
PUBSUB_BOOKING_TOPIC="${PUBSUB_BOOKING_TOPIC:-booking-events}"

read -rp "Enter PUBSUB_REPORT_TOPIC (default: report-events): " PUBSUB_REPORT_TOPIC
PUBSUB_REPORT_TOPIC="${PUBSUB_REPORT_TOPIC:-report-events}"

read -rp "Enter REPORT_BASE_PATH (default: /var/reports): " REPORT_BASE_PATH
REPORT_BASE_PATH="${REPORT_BASE_PATH:-/var/reports}"

read -rp "Enter BOOKING_CACHE_TTL_SECONDS (default: 3600): " BOOKING_CACHE_TTL_SECONDS
BOOKING_CACHE_TTL_SECONDS="${BOOKING_CACHE_TTL_SECONDS:-3600}"

read -rp "Enter PAYMENT_API_URL (default: http://payment-service:9090/payments/charge): " PAYMENT_API_URL
PAYMENT_API_URL="${PAYMENT_API_URL:-http://payment-service:9090/payments/charge}"

echo ""
echo "=============================================="
echo "  Configuring kubectl for GKE cluster"
echo "=============================================="
gcloud container clusters get-credentials "$CLUSTER_NAME" --zone "$GCP_ZONE" --project "$GCP_PROJECT"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "❌ Cannot connect to cluster. Exiting."; exit 1; }

echo ""
echo "=============================================="
echo "  Updating Kubernetes manifests"
echo "=============================================="

# Replace placeholders in deployment.yaml using pipe delimiter
sed -i 's|{{IMAGE_URI}}|'"$IMAGE_URI"'|g'                                   kubernetes/deployment.yaml
sed -i 's|{{REDIS_HOST}}|'"$REDIS_HOST"'|g'                                 kubernetes/deployment.yaml
sed -i 's|{{REDIS_PORT}}|'"$REDIS_PORT"'|g'                                 kubernetes/deployment.yaml
sed -i 's|{{GCP_PROJECT_ID}}|'"$PUBSUB_GCP_PROJECT"'|g'                     kubernetes/deployment.yaml
sed -i 's|{{PUBSUB_BOOKING_TOPIC}}|'"$PUBSUB_BOOKING_TOPIC"'|g'             kubernetes/deployment.yaml
sed -i 's|{{PUBSUB_REPORT_TOPIC}}|'"$PUBSUB_REPORT_TOPIC"'|g'               kubernetes/deployment.yaml
sed -i 's|{{REPORT_BASE_PATH}}|'"$REPORT_BASE_PATH"'|g'                     kubernetes/deployment.yaml
sed -i 's|{{BOOKING_CACHE_TTL_SECONDS}}|'"$BOOKING_CACHE_TTL_SECONDS"'|g'   kubernetes/deployment.yaml
sed -i 's|{{PAYMENT_API_URL}}|'"$PAYMENT_API_URL"'|g'                       kubernetes/deployment.yaml

echo ""
echo "=============================================="
echo "  Applying Kubernetes manifests"
echo "=============================================="

echo "1/4 Applying namespace..."
kubectl apply -f kubernetes/namespace.yaml

echo "2/4 Applying deployment..."
kubectl apply -f kubernetes/deployment.yaml

echo "3/4 Applying service..."
kubectl apply -f kubernetes/service.yaml

echo "4/4 Applying ingress..."
kubectl apply -f kubernetes/ingress.yaml

echo ""
echo "=============================================="
echo "  Waiting for rollout to complete"
echo "=============================================="
kubectl rollout status deployment/resortslite -n resortslite --timeout=300s

echo ""
echo "=============================================="
echo "  Deployment verification"
echo "=============================================="
kubectl get pods,svc,ingress -n resortslite

echo ""
INGRESS_IP=$(kubectl get ingress resortslite-ingress -n resortslite -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "pending")
echo "✅ Deployment complete!"
echo "   Ingress IP : $INGRESS_IP"
echo "   Application: http://$INGRESS_IP"
echo ""
echo "If the ingress IP is still 'pending', run:"
echo "  kubectl get ingress resortslite-ingress -n resortslite"
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/resortslite -n resortslite"
