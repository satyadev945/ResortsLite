#!/bin/bash
set -e
set -o pipefail

APP_NAME="resortslite"
NAMESPACE="resortslite"

echo "============================================"
echo "  ResortsLite - Deploy to Azure AKS"
echo "============================================"
echo ""

# Prompt for Azure details
read -p "Enter Azure Resource Group name: " RESOURCE_GROUP
if [ -z "$RESOURCE_GROUP" ]; then
  echo "Resource group cannot be empty. Exiting."
  exit 1
fi

read -p "Enter AKS Cluster name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "AKS cluster name cannot be empty. Exiting."
  exit 1
fi

read -p "Enter full Docker image URI (e.g., myregistry.azurecr.io/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "Image URI cannot be empty. Exiting."
  exit 1
fi

echo ""
echo "--- Application Environment Variables ---"
echo "Press Enter to skip any variable (placeholder will remain in manifest)."
echo ""

read -p "Enter SPRING_DATASOURCE_URL (e.g., jdbc:oracle:thin:@host:1521:SID): " SPRING_DATASOURCE_URL_VAL
read -p "Enter SPRING_DATASOURCE_USERNAME: " SPRING_DATASOURCE_USERNAME_VAL
read -s -p "Enter SPRING_DATASOURCE_PASSWORD: " SPRING_DATASOURCE_PASSWORD_VAL
echo ""
read -p "Enter REDIS_HOST (Azure Cache for Redis hostname): " REDIS_HOST_VAL
read -p "Enter REDIS_PORT (default 6380 for Azure SSL): " REDIS_PORT_VAL
read -s -p "Enter REDIS_PASSWORD: " REDIS_PASSWORD_VAL
echo ""
read -p "Enter REDIS_SSL (true/false, default true): " REDIS_SSL_VAL
read -p "Enter APP_PAYMENT_ENDPOINT: " APP_PAYMENT_ENDPOINT_VAL
read -p "Enter APP_INVENTORY_ENDPOINT: " APP_INVENTORY_ENDPOINT_VAL
read -p "Enter APP_NOTIFICATION_ENDPOINT: " APP_NOTIFICATION_ENDPOINT_VAL
read -p "Enter REPORT_BASE_PATH (default /var/reports): " REPORT_BASE_PATH_VAL
read -p "Enter BACKUP_PATH (default /var/backups/nightly): " BACKUP_PATH_VAL

# Set defaults for optional values
[ -z "$REDIS_PORT_VAL" ] && REDIS_PORT_VAL="6380"
[ -z "$REDIS_SSL_VAL" ] && REDIS_SSL_VAL="true"
[ -z "$REPORT_BASE_PATH_VAL" ] && REPORT_BASE_PATH_VAL="/var/reports"
[ -z "$BACKUP_PATH_VAL" ] && BACKUP_PATH_VAL="/var/backups/nightly"

echo ""
echo "--- Configuring kubectl for AKS ---"
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing
echo "kubectl configured successfully."

echo ""
echo "--- Verifying cluster connectivity ---"
kubectl cluster-info || { echo "Cannot connect to AKS cluster. Exiting."; exit 1; }

echo ""
echo "--- Updating Kubernetes manifests ---"

# Work on copies to avoid modifying originals
cp kubernetes/deployment.yaml kubernetes/deployment.yaml.bak

# Replace IMAGE_URI placeholder
sed -i 's|{{IMAGE_URI}}|'"$IMAGE_URI"'|g' kubernetes/deployment.yaml

# Replace environment variable placeholders
[ -n "$SPRING_DATASOURCE_URL_VAL" ]      && sed -i 's|{{SPRING_DATASOURCE_URL}}|'"$SPRING_DATASOURCE_URL_VAL"'|g'           kubernetes/deployment.yaml
[ -n "$SPRING_DATASOURCE_USERNAME_VAL" ] && sed -i 's|{{SPRING_DATASOURCE_USERNAME}}|'"$SPRING_DATASOURCE_USERNAME_VAL"'|g'   kubernetes/deployment.yaml
[ -n "$SPRING_DATASOURCE_PASSWORD_VAL" ] && sed -i 's|{{SPRING_DATASOURCE_PASSWORD}}|'"$SPRING_DATASOURCE_PASSWORD_VAL"'|g'   kubernetes/deployment.yaml
[ -n "$REDIS_HOST_VAL" ]                 && sed -i 's|{{REDIS_HOST}}|'"$REDIS_HOST_VAL"'|g'                                   kubernetes/deployment.yaml
sed -i 's|{{REDIS_PORT}}|'"$REDIS_PORT_VAL"'|g'                                                                                kubernetes/deployment.yaml
[ -n "$REDIS_PASSWORD_VAL" ]             && sed -i 's|{{REDIS_PASSWORD}}|'"$REDIS_PASSWORD_VAL"'|g'                           kubernetes/deployment.yaml
sed -i 's|{{REDIS_SSL}}|'"$REDIS_SSL_VAL"'|g'                                                                                  kubernetes/deployment.yaml
[ -n "$APP_PAYMENT_ENDPOINT_VAL" ]       && sed -i 's|{{APP_PAYMENT_ENDPOINT}}|'"$APP_PAYMENT_ENDPOINT_VAL"'|g'               kubernetes/deployment.yaml
[ -n "$APP_INVENTORY_ENDPOINT_VAL" ]     && sed -i 's|{{APP_INVENTORY_ENDPOINT}}|'"$APP_INVENTORY_ENDPOINT_VAL"'|g'           kubernetes/deployment.yaml
[ -n "$APP_NOTIFICATION_ENDPOINT_VAL" ]  && sed -i 's|{{APP_NOTIFICATION_ENDPOINT}}|'"$APP_NOTIFICATION_ENDPOINT_VAL"'|g'     kubernetes/deployment.yaml
sed -i 's|{{REPORT_BASE_PATH}}|'"$REPORT_BASE_PATH_VAL"'|g'                                                                    kubernetes/deployment.yaml
sed -i 's|{{BACKUP_PATH}}|'"$BACKUP_PATH_VAL"'|g'                                                                              kubernetes/deployment.yaml

echo "Manifests updated."

echo ""
echo "--- Applying Kubernetes manifests ---"
echo "Applying namespace..."
kubectl apply -f kubernetes/namespace.yaml

echo "Applying deployment..."
kubectl apply -f kubernetes/deployment.yaml

echo "Applying service..."
kubectl apply -f kubernetes/service.yaml

echo "Applying ingress..."
kubectl apply -f kubernetes/ingress.yaml

echo ""
echo "--- Waiting for deployment rollout ---"
kubectl rollout status deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=300s

echo ""
echo "--- Verifying deployed resources ---"
kubectl get pods,svc,ingress -n "$NAMESPACE"

echo ""
echo "--- Application Access ---"
INGRESS_IP=$(kubectl get ingress resortslite-ingress -n "$NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "pending")
echo "Ingress IP: $INGRESS_IP"
echo "Application URL: http://resortslite.example.com"
echo "(Update your DNS to point resortslite.example.com -> $INGRESS_IP)"

echo ""
echo "--- Rollback Instructions ---"
echo "If deployment fails, run:"
echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"

# Restore original deployment.yaml
mv kubernetes/deployment.yaml.bak kubernetes/deployment.yaml

echo ""
echo "============================================"
echo "  Deployment Complete!"
echo "============================================"
