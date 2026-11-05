#!/bin/bash
set -e
set -o pipefail

# ============================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# Usage: ./scripts/deploy-image.sh
# ============================================================

APP_NAME="resortslite"
NAMESPACE="resortslite"
K8S_DIR="kubernetes"

echo "=============================================="
echo "  ResortsLite — AWS EKS Deployment Script"
echo "=============================================="
echo ""

# ---- Prompt for AWS / EKS details ----
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
echo "Press Enter to skip any variable (default value will be used)."
echo ""

read -rp "Enter SPRING_DATASOURCE_URL (e.g. jdbc:oracle:thin:@host:1521:ORCL): " SPRING_DATASOURCE_URL
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:oracle:thin:@localhost:1521:ORCL}"

read -rp "Enter SPRING_DATASOURCE_USERNAME [admin]: " SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-admin}"

read -rsp "Enter SPRING_DATASOURCE_PASSWORD: " SPRING_DATASOURCE_PASSWORD
echo ""
SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-changeme}"

read -rp "Enter REDIS_HOST (ElastiCache endpoint) [localhost]: " REDIS_HOST
REDIS_HOST="${REDIS_HOST:-localhost}"

read -rp "Enter REDIS_PORT [6379]: " REDIS_PORT
REDIS_PORT="${REDIS_PORT:-6379}"

read -rp "Enter REDIS_SSL (true/false) [false]: " REDIS_SSL
REDIS_SSL="${REDIS_SSL:-false}"

read -rp "Enter BOOKING_CACHE_TTL_SECONDS [3600]: " BOOKING_CACHE_TTL_SECONDS
BOOKING_CACHE_TTL_SECONDS="${BOOKING_CACHE_TTL_SECONDS:-3600}"

read -rp "Enter PAYMENT_SERVICE_URL [http://payment-service:9090/payments/charge]: " PAYMENT_SERVICE_URL
PAYMENT_SERVICE_URL="${PAYMENT_SERVICE_URL:-http://payment-service:9090/payments/charge}"

echo ""
echo "---- Configuring kubectl for EKS ----"
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster."; exit 1; }

echo ""
echo "---- Updating Kubernetes manifests ----"

# Replace IMAGE_URI placeholder
sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g" "${K8S_DIR}/deployment.yaml"

# Replace environment variable placeholders
sed -i "s|{{SPRING_DATASOURCE_URL}}|${SPRING_DATASOURCE_URL}|g"         "${K8S_DIR}/deployment.yaml"
sed -i "s|{{SPRING_DATASOURCE_USERNAME}}|${SPRING_DATASOURCE_USERNAME}|g" "${K8S_DIR}/deployment.yaml"
sed -i "s|{{SPRING_DATASOURCE_PASSWORD}}|${SPRING_DATASOURCE_PASSWORD}|g" "${K8S_DIR}/deployment.yaml"
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST}|g"                                 "${K8S_DIR}/deployment.yaml"
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT}|g"                                 "${K8S_DIR}/deployment.yaml"
sed -i "s|{{REDIS_SSL}}|${REDIS_SSL}|g"                                   "${K8S_DIR}/deployment.yaml"
sed -i "s|{{BOOKING_CACHE_TTL_SECONDS}}|${BOOKING_CACHE_TTL_SECONDS}|g"   "${K8S_DIR}/deployment.yaml"
sed -i "s|{{PAYMENT_SERVICE_URL}}|${PAYMENT_SERVICE_URL}|g"               "${K8S_DIR}/deployment.yaml"

echo ""
echo "---- Applying Kubernetes manifests ----"
kubectl apply -f "${K8S_DIR}/namespace.yaml"
kubectl apply -f "${K8S_DIR}/deployment.yaml"
kubectl apply -f "${K8S_DIR}/service.yaml"
kubectl apply -f "${K8S_DIR}/ingress.yaml"

echo ""
echo "---- Waiting for deployment rollout ----"
kubectl rollout status deployment/"${APP_NAME}" -n "${NAMESPACE}" --timeout=300s

echo ""
echo "---- Verifying deployed resources ----"
kubectl get pods,svc,ingress -n "${NAMESPACE}"

echo ""
echo "---- Application Access URL ----"
INGRESS_HOST=$(kubectl get ingress "${APP_NAME}-ingress" -n "${NAMESPACE}" \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "pending")
echo "Ingress hostname: ${INGRESS_HOST}"
echo "Application URL : http://${INGRESS_HOST}"
echo ""
echo "NOTE: If hostname shows 'pending', wait a few minutes for the AWS ALB to provision."
echo ""
echo "---- Rollback Instructions ----"
echo "To rollback to the previous version, run:"
echo "  kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"
echo ""
echo "=============================================="
echo "  Deployment Complete!"
echo "=============================================="
