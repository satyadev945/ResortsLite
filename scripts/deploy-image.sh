#!/bin/bash
set -e
set -o pipefail

# =============================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# =============================================================

APP_NAME="resortslite"
NAMESPACE="resortslite"

echo "============================================"
echo "  ResortsLite — Deploy to AWS EKS"
echo "============================================"

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

# Prompt for application environment variables
echo ""
echo "--- Application Configuration (press Enter to skip optional values) ---"

read -rp "Enter SPRING_DATASOURCE_URL (Oracle JDBC URL): " SPRING_DATASOURCE_URL
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

read -rsp "Enter REDIS_PASSWORD (leave blank if none): " REDIS_PASSWORD
echo ""

read -rp "Enable REDIS_SSL? [false]: " REDIS_SSL
REDIS_SSL="${REDIS_SSL:-false}"

read -rp "Enter PAYMENT_API_URL [http://payment-service.payments.svc.cluster.local:9090/payments/charge]: " PAYMENT_API_URL
PAYMENT_API_URL="${PAYMENT_API_URL:-http://payment-service.payments.svc.cluster.local:9090/payments/charge}"

echo ""
echo "Configuring kubectl for EKS cluster: $CLUSTER_NAME in $AWS_REGION ..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster." >&2; exit 1; }

echo ""
echo "Updating Kubernetes manifests with provided values..."

# Replace placeholders in deployment.yaml using pipe delimiter
sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g" kubernetes/deployment.yaml
sed -i "s|{{SPRING_DATASOURCE_URL}}|${SPRING_DATASOURCE_URL}|g" kubernetes/deployment.yaml
sed -i "s|{{SPRING_DATASOURCE_USERNAME}}|${SPRING_DATASOURCE_USERNAME}|g" kubernetes/deployment.yaml
sed -i "s|{{SPRING_DATASOURCE_PASSWORD}}|${SPRING_DATASOURCE_PASSWORD}|g" kubernetes/deployment.yaml
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST}|g" kubernetes/deployment.yaml
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT}|g" kubernetes/deployment.yaml
sed -i "s|{{REDIS_PASSWORD}}|${REDIS_PASSWORD}|g" kubernetes/deployment.yaml
sed -i "s|{{REDIS_SSL}}|${REDIS_SSL}|g" kubernetes/deployment.yaml
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL}|g" kubernetes/deployment.yaml

echo ""
echo "Applying Kubernetes manifests..."

echo "  [1/4] Applying namespace..."
kubectl apply -f kubernetes/namespace.yaml

echo "  [2/4] Applying deployment..."
kubectl apply -f kubernetes/deployment.yaml

echo "  [3/4] Applying service..."
kubectl apply -f kubernetes/service.yaml

echo "  [4/4] Applying ingress..."
kubectl apply -f kubernetes/ingress.yaml

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

echo ""
echo "============================================"
echo "  Deployment Complete!"
echo "  Application URL: http://${INGRESS_HOST}"
echo "  Namespace: ${NAMESPACE}"
echo "============================================"
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"
