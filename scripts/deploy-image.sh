#!/bin/bash
set -e
set -o pipefail

# ============================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# ============================================================

APP_NAME="resortslite"
NAMESPACE="resortslite"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "============================================"
echo "  ResortsLite — Deploy to AWS EKS"
echo "============================================"
echo ""

# ---- AWS / EKS configuration ----
read -rp "Enter AWS Region [us-east-1]: " AWS_REGION
AWS_REGION="${AWS_REGION:-us-east-1}"

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
echo "---- Application Environment Variables ----"
echo "Press Enter to skip any variable (placeholder will remain in manifest)."
echo ""

read -rp "Enter REDIS_HOST (ElastiCache endpoint): " REDIS_HOST
REDIS_HOST="${REDIS_HOST:-localhost}"

read -rp "Enter REDIS_PORT [6379]: " REDIS_PORT
REDIS_PORT="${REDIS_PORT:-6379}"

read -rp "Enter SPRING_DATASOURCE_URL (Oracle JDBC URL): " SPRING_DATASOURCE_URL
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:oracle:thin:@localhost:1521:ORCL}"

read -rp "Enter SPRING_DATASOURCE_USERNAME: " SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-admin}"

read -rsp "Enter SPRING_DATASOURCE_PASSWORD: " SPRING_DATASOURCE_PASSWORD
echo ""
SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-changeme}"

read -rp "Enter PAYMENT_API_URL: " PAYMENT_API_URL
PAYMENT_API_URL="${PAYMENT_API_URL:-http://payment-svc.payments.svc.cluster.local:9090/payments/charge}"

read -rp "Enter APP_PAYMENT_ENDPOINT: " APP_PAYMENT_ENDPOINT
APP_PAYMENT_ENDPOINT="${APP_PAYMENT_ENDPOINT:-http://payment-svc.internal:9090/charge}"

read -rp "Enter APP_INVENTORY_ENDPOINT: " APP_INVENTORY_ENDPOINT
APP_INVENTORY_ENDPOINT="${APP_INVENTORY_ENDPOINT:-http://inventory-svc.internal:8081/rooms}"

read -rp "Enter APP_NOTIFICATION_ENDPOINT: " APP_NOTIFICATION_ENDPOINT
APP_NOTIFICATION_ENDPOINT="${APP_NOTIFICATION_ENDPOINT:-http://notify.internal:7070/send}"

read -rp "Enter REPORT_BASE_PATH [/var/reports]: " REPORT_BASE_PATH
REPORT_BASE_PATH="${REPORT_BASE_PATH:-/var/reports}"

# ---- Configure kubectl ----
echo ""
echo "Configuring kubectl for EKS cluster: $CLUSTER_NAME ..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster." >&2; exit 1; }

# ---- Patch manifests ----
echo ""
echo "Updating Kubernetes manifests with provided values..."

DEPLOY_YAML="$PROJECT_ROOT/kubernetes/deployment.yaml"

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                                   "$DEPLOY_YAML"
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST}|g"                                 "$DEPLOY_YAML"
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT}|g"                                 "$DEPLOY_YAML"
sed -i "s|{{SPRING_DATASOURCE_URL}}|${SPRING_DATASOURCE_URL}|g"           "$DEPLOY_YAML"
sed -i "s|{{SPRING_DATASOURCE_USERNAME}}|${SPRING_DATASOURCE_USERNAME}|g" "$DEPLOY_YAML"
sed -i "s|{{SPRING_DATASOURCE_PASSWORD}}|${SPRING_DATASOURCE_PASSWORD}|g" "$DEPLOY_YAML"
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL}|g"                       "$DEPLOY_YAML"
sed -i "s|{{APP_PAYMENT_ENDPOINT}}|${APP_PAYMENT_ENDPOINT}|g"             "$DEPLOY_YAML"
sed -i "s|{{APP_INVENTORY_ENDPOINT}}|${APP_INVENTORY_ENDPOINT}|g"         "$DEPLOY_YAML"
sed -i "s|{{APP_NOTIFICATION_ENDPOINT}}|${APP_NOTIFICATION_ENDPOINT}|g"   "$DEPLOY_YAML"
sed -i "s|{{REPORT_BASE_PATH}}|${REPORT_BASE_PATH}|g"                     "$DEPLOY_YAML"

# ---- Apply manifests ----
echo ""
echo "Applying Kubernetes manifests..."

echo "  [1/4] Applying namespace..."
kubectl apply -f "$PROJECT_ROOT/kubernetes/namespace.yaml"

echo "  [2/4] Applying deployment..."
kubectl apply -f "$PROJECT_ROOT/kubernetes/deployment.yaml"

echo "  [3/4] Applying service..."
kubectl apply -f "$PROJECT_ROOT/kubernetes/service.yaml"

echo "  [4/4] Applying ingress..."
kubectl apply -f "$PROJECT_ROOT/kubernetes/ingress.yaml"

# ---- Wait for rollout ----
echo ""
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=300s

# ---- Verify ----
echo ""
echo "Verifying deployed resources..."
kubectl get pods,svc,ingress -n "$NAMESPACE"

# ---- Display URL ----
echo ""
INGRESS_HOST=$(kubectl get ingress resortslite-ingress -n "$NAMESPACE" \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "<pending>")
echo "============================================"
echo "  Deployment Complete!"
echo "  Application URL: http://${INGRESS_HOST}"
echo "  Health Check:    http://${INGRESS_HOST}/actuator/health"
echo "============================================"
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
