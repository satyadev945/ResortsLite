#!/usr/bin/env bash
# =============================================================================
# deploy-image.sh — Deploy ResortsLite to AWS ECS Fargate
# Usage: bash scripts/deploy-image.sh   (run from repository root)
# =============================================================================
set -e
set -o pipefail

SERVICE_NAME="resortsLite-service"
TASK_FAMILY="resortsLite-task"
CONTAINER_NAME="resortsLite"
APP_PORT=8080
LOG_GROUP="/ecs/resortsLite"
TASK_DEF_FILE="ecs/task-definition.json"
SVC_DEF_FILE="ecs/service-definition.json"

echo "=============================================="
echo "  ResortsLite — ECS Fargate Deployment"
echo "=============================================="
echo ""

# ---------------------------------------------------------------------------
# Collect inputs
# ---------------------------------------------------------------------------
read -rp "AWS Region [us-east-1]: " AWS_REGION
AWS_REGION="${AWS_REGION:-us-east-1}"

read -rp "ECS Cluster name [resortsLite-cluster]: " CLUSTER_NAME
CLUSTER_NAME="${CLUSTER_NAME:-resortsLite-cluster}"

read -rp "ECR Image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortsLite:latest): " IMAGE_URI
if [ -z "${IMAGE_URI}" ]; then
  echo "ERROR: Image URI is required."
  exit 1
fi

read -rp "VPC ID (e.g. vpc-xxxxxxxx): " VPC_ID
read -rp "Subnet IDs — comma-separated, at least 2 (e.g. subnet-aaa,subnet-bbb): " SUBNETS_RAW
read -rp "Security Group ID (e.g. sg-xxxxxxxx): " SECURITY_GROUP

# Parse subnets
SUBNET_1=$(echo "${SUBNETS_RAW}" | cut -d',' -f1 | tr -d ' ')
SUBNET_2=$(echo "${SUBNETS_RAW}" | cut -d',' -f2 | tr -d ' ')
if [ -z "${SUBNET_2}" ]; then
  SUBNET_2="${SUBNET_1}"
fi

# ---------------------------------------------------------------------------
# Derive AWS Account ID
# ---------------------------------------------------------------------------
echo ""
echo "Fetching AWS Account ID..."
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "Account ID: ${ACCOUNT_ID}"

# ---------------------------------------------------------------------------
# Ensure CloudWatch log group exists
# ---------------------------------------------------------------------------
echo "Ensuring CloudWatch log group '${LOG_GROUP}' exists..."
aws logs create-log-group --log-group-name "${LOG_GROUP}" --region "${AWS_REGION}" 2>/dev/null || true

# ---------------------------------------------------------------------------
# Ensure ECS cluster exists
# ---------------------------------------------------------------------------
echo "Checking ECS cluster '${CLUSTER_NAME}'..."
CLUSTER_STATUS=$(aws ecs describe-clusters --clusters "${CLUSTER_NAME}" --region "${AWS_REGION}" \
  --query "clusters[0].status" --output text 2>/dev/null || echo "MISSING")
if [ "${CLUSTER_STATUS}" != "ACTIVE" ]; then
  echo "Creating ECS cluster '${CLUSTER_NAME}'..."
  aws ecs create-cluster --cluster-name "${CLUSTER_NAME}" --region "${AWS_REGION}"
fi
echo "Cluster ready."

# ---------------------------------------------------------------------------
# Load balancer (optional)
# ---------------------------------------------------------------------------
echo ""
read -rp "Do you need an Application Load Balancer for this service? (y/n) [n]: " NEED_LB
NEED_LB="${NEED_LB:-n}"

TARGET_GROUP_ARN=""
LB_DNS=""

if [[ "${NEED_LB}" =~ ^[Yy]$ ]]; then
  echo ""
  echo "Creating Application Load Balancer..."

  LB_NAME="resortsLite-alb"
  TG_NAME="resortsLite-tg"

  # Create ALB
  LB_ARN=$(aws elbv2 create-load-balancer \
    --name "${LB_NAME}" \
    --subnets ${SUBNET_1} ${SUBNET_2} \
    --security-groups "${SECURITY_GROUP}" \
    --scheme internet-facing \
    --type application \
    --region "${AWS_REGION}" \
    --query "LoadBalancers[0].LoadBalancerArn" --output text)
  echo "ALB ARN: ${LB_ARN}"

  LB_DNS=$(aws elbv2 describe-load-balancers \
    --load-balancer-arns "${LB_ARN}" \
    --region "${AWS_REGION}" \
    --query "LoadBalancers[0].DNSName" --output text)

  # Create Target Group (target-type ip — required for Fargate awsvpc)
  TARGET_GROUP_ARN=$(aws elbv2 create-target-group \
    --name "${TG_NAME}" \
    --protocol HTTP \
    --port "${APP_PORT}" \
    --vpc-id "${VPC_ID}" \
    --target-type ip \
    --health-check-path "/actuator/health" \
    --health-check-interval-seconds 30 \
    --healthy-threshold-count 2 \
    --unhealthy-threshold-count 3 \
    --region "${AWS_REGION}" \
    --query "TargetGroups[0].TargetGroupArn" --output text)
  echo "Target Group ARN: ${TARGET_GROUP_ARN}"

  # Create listener
  aws elbv2 create-listener \
    --load-balancer-arn "${LB_ARN}" \
    --protocol HTTP \
    --port 80 \
    --default-actions Type=forward,TargetGroupArn="${TARGET_GROUP_ARN}" \
    --region "${AWS_REGION}" >/dev/null
  echo "ALB listener created."
fi

# ---------------------------------------------------------------------------
# Substitute placeholders in task definition
# ---------------------------------------------------------------------------
echo ""
echo "Preparing task definition..."
TMP_TASK_DEF="/tmp/resortsLite-task-def-$$.json"
cp "${TASK_DEF_FILE}" "${TMP_TASK_DEF}"

sed -i "s|{{ACCOUNT_ID}}|${ACCOUNT_ID}|g"   "${TMP_TASK_DEF}"
sed -i "s|{{AWS_REGION}}|${AWS_REGION}|g"   "${TMP_TASK_DEF}"
sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"      "${TMP_TASK_DEF}"

# ---------------------------------------------------------------------------
# Register task definition
# ---------------------------------------------------------------------------
echo "Registering task definition..."
TASK_DEF_ARN=$(aws ecs register-task-definition \
  --cli-input-json "file://${TMP_TASK_DEF}" \
  --region "${AWS_REGION}" \
  --query "taskDefinition.taskDefinitionArn" --output text)
echo "Task Definition ARN: ${TASK_DEF_ARN}"
rm -f "${TMP_TASK_DEF}"

# ---------------------------------------------------------------------------
# Prepare service definition
# ---------------------------------------------------------------------------
TMP_SVC_DEF="/tmp/resortsLite-svc-def-$$.json"
cp "${SVC_DEF_FILE}" "${TMP_SVC_DEF}"

sed -i "s|{{CLUSTER_NAME}}|${CLUSTER_NAME}|g"     "${TMP_SVC_DEF}"
sed -i "s|{{SUBNET_1}}|${SUBNET_1}|g"             "${TMP_SVC_DEF}"
sed -i "s|{{SUBNET_2}}|${SUBNET_2}|g"             "${TMP_SVC_DEF}"
sed -i "s|{{SECURITY_GROUP}}|${SECURITY_GROUP}|g" "${TMP_SVC_DEF}"

# Inject load balancer section if needed
if [[ "${NEED_LB}" =~ ^[Yy]$ ]]; then
  # Use Python to inject loadBalancers array into the service JSON
  python3 - <<PYEOF
import json, sys
with open("${TMP_SVC_DEF}") as f:
    svc = json.load(f)
svc["loadBalancers"] = [{
    "targetGroupArn": "${TARGET_GROUP_ARN}",
    "containerName": "${CONTAINER_NAME}",
    "containerPort": ${APP_PORT}
}]
svc["healthCheckGracePeriodSeconds"] = 300
with open("${TMP_SVC_DEF}", "w") as f:
    json.dump(svc, f, indent=2)
PYEOF
fi

# ---------------------------------------------------------------------------
# Create or update ECS service
# ---------------------------------------------------------------------------
echo ""
echo "Checking if ECS service '${SERVICE_NAME}' exists..."
EXISTING_SERVICE=$(aws ecs describe-services \
  --cluster "${CLUSTER_NAME}" \
  --services "${SERVICE_NAME}" \
  --region "${AWS_REGION}" \
  --query "services[?status!='INACTIVE'].serviceName" \
  --output text 2>/dev/null || echo "")

if [ -z "${EXISTING_SERVICE}" ] || [ "${EXISTING_SERVICE}" = "None" ]; then
  echo "Creating ECS service '${SERVICE_NAME}'..."
  aws ecs create-service \
    --cli-input-json "file://${TMP_SVC_DEF}" \
    --region "${AWS_REGION}"
  echo "Service created."
else
  echo "Updating existing ECS service '${SERVICE_NAME}'..."
  aws ecs update-service \
    --cluster "${CLUSTER_NAME}" \
    --service "${SERVICE_NAME}" \
    --task-definition "${TASK_DEF_ARN}" \
    --region "${AWS_REGION}" >/dev/null
  echo "Service updated."
fi

rm -f "${TMP_SVC_DEF}"

# ---------------------------------------------------------------------------
# Wait for stability
# ---------------------------------------------------------------------------
echo ""
echo "Waiting for service to stabilise (this may take a few minutes)..."
aws ecs wait services-stable \
  --cluster "${CLUSTER_NAME}" \
  --services "${SERVICE_NAME}" \
  --region "${AWS_REGION}"

# ---------------------------------------------------------------------------
# Verify deployment
# ---------------------------------------------------------------------------
echo ""
echo "Deployment verification:"
aws ecs describe-services \
  --cluster "${CLUSTER_NAME}" \
  --services "${SERVICE_NAME}" \
  --region "${AWS_REGION}" \
  --query "services[0].{Status:status,Running:runningCount,Desired:desiredCount,Pending:pendingCount}"

echo ""
echo "=============================================="
echo "  Deployment complete!"
echo "  Service  : ${SERVICE_NAME}"
echo "  Cluster  : ${CLUSTER_NAME}"
echo "  Region   : ${AWS_REGION}"
echo "  Log Group: ${LOG_GROUP}"
if [ -n "${LB_DNS}" ]; then
  echo "  ALB URL  : http://${LB_DNS}"
fi
echo "=============================================="
echo ""
echo "Troubleshooting tips:"
echo "  View logs : aws logs tail ${LOG_GROUP} --follow --region ${AWS_REGION}"
echo "  List tasks: aws ecs list-tasks --cluster ${CLUSTER_NAME} --service-name ${SERVICE_NAME} --region ${AWS_REGION}"
echo "  Stop svc  : aws ecs update-service --cluster ${CLUSTER_NAME} --service ${SERVICE_NAME} --desired-count 0 --region ${AWS_REGION}"
