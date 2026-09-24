#!/usr/bin/env bash
# =============================================================================
# deploy-image.sh — Deploy ResortsLite to AWS ECS Fargate
# Usage: bash scripts/deploy-image.sh   (run from repository root)
# =============================================================================
set -e
set -o pipefail

PROJECT_NAME="resortsLite"
SERVICE_NAME="${PROJECT_NAME}-service"
TASK_FAMILY="${PROJECT_NAME}-task"
LOG_GROUP="/ecs/${PROJECT_NAME}"
TASK_DEF_FILE="ecs/task-definition.json"
SERVICE_DEF_FILE="ecs/service-definition.json"

echo "=============================================="
echo "  ResortsLite — ECS Fargate Deployment"
echo "=============================================="
echo ""

# ---------------------------------------------------------------------------
# Collect configuration
# ---------------------------------------------------------------------------
read -rp "AWS Region [us-east-1]: " AWS_REGION
AWS_REGION="${AWS_REGION:-us-east-1}"

read -rp "ECS Cluster name [resortsLite-cluster]: " CLUSTER_NAME
CLUSTER_NAME="${CLUSTER_NAME:-resortsLite-cluster}"

read -rp "ECR Image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortsLite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Image URI is required."
  exit 1
fi

echo ""
echo "--- Network Configuration ---"
read -rp "VPC ID: " VPC_ID
read -rp "Subnet 1 ID: " SUBNET_1
read -rp "Subnet 2 ID: " SUBNET_2
read -rp "Security Group ID: " SECURITY_GROUP

if [ -z "$SUBNET_1" ] || [ -z "$SUBNET_2" ] || [ -z "$SECURITY_GROUP" ]; then
  echo "ERROR: Subnets and Security Group are required."
  exit 1
fi

# ---------------------------------------------------------------------------
# Resolve AWS Account ID
# ---------------------------------------------------------------------------
echo ""
echo "Resolving AWS Account ID..."
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "Account ID: $ACCOUNT_ID"

# ---------------------------------------------------------------------------
# Ensure CloudWatch log group exists
# ---------------------------------------------------------------------------
echo ""
echo "Ensuring CloudWatch log group exists: $LOG_GROUP"
aws logs create-log-group --log-group-name "$LOG_GROUP" --region "$AWS_REGION" 2>/dev/null || true

# ---------------------------------------------------------------------------
# Ensure ECS cluster exists
# ---------------------------------------------------------------------------
echo ""
echo "Checking ECS cluster: $CLUSTER_NAME"
CLUSTER_STATUS=$(aws ecs describe-clusters --clusters "$CLUSTER_NAME" --region "$AWS_REGION" \
  --query "clusters[0].status" --output text 2>/dev/null || echo "MISSING")

if [ "$CLUSTER_STATUS" != "ACTIVE" ]; then
  echo "Creating ECS cluster: $CLUSTER_NAME"
  aws ecs create-cluster --cluster-name "$CLUSTER_NAME" --region "$AWS_REGION"
else
  echo "Cluster already exists and is ACTIVE."
fi

# ---------------------------------------------------------------------------
# Load Balancer (optional)
# ---------------------------------------------------------------------------
echo ""
read -rp "Do you need an Application Load Balancer for this service? (y/n) [n]: " NEED_ALB
NEED_ALB="${NEED_ALB:-n}"

TARGET_GROUP_ARN=""
ALB_DNS=""

if [[ "$NEED_ALB" =~ ^[Yy]$ ]]; then
  echo ""
  echo "Creating Application Load Balancer..."

  ALB_NAME="${PROJECT_NAME}-alb"
  TG_NAME="${PROJECT_NAME}-tg"

  # Create ALB
  ALB_ARN=$(aws elbv2 create-load-balancer \
    --name "$ALB_NAME" \
    --subnets "$SUBNET_1" "$SUBNET_2" \
    --security-groups "$SECURITY_GROUP" \
    --scheme internet-facing \
    --type application \
    --region "$AWS_REGION" \
    --query "LoadBalancers[0].LoadBalancerArn" \
    --output text)
  echo "ALB ARN: $ALB_ARN"

  ALB_DNS=$(aws elbv2 describe-load-balancers \
    --load-balancer-arns "$ALB_ARN" \
    --region "$AWS_REGION" \
    --query "LoadBalancers[0].DNSName" \
    --output text)

  # Create Target Group (target-type ip required for Fargate awsvpc mode)
  TARGET_GROUP_ARN=$(aws elbv2 create-target-group \
    --name "$TG_NAME" \
    --protocol HTTP \
    --port 8080 \
    --vpc-id "$VPC_ID" \
    --target-type ip \
    --health-check-path "/actuator/health" \
    --health-check-interval-seconds 30 \
    --healthy-threshold-count 2 \
    --unhealthy-threshold-count 3 \
    --region "$AWS_REGION" \
    --query "TargetGroups[0].TargetGroupArn" \
    --output text)
  echo "Target Group ARN: $TARGET_GROUP_ARN"

  # Create listener
  aws elbv2 create-listener \
    --load-balancer-arn "$ALB_ARN" \
    --protocol HTTP \
    --port 80 \
    --default-actions "Type=forward,TargetGroupArn=${TARGET_GROUP_ARN}" \
    --region "$AWS_REGION" >/dev/null

  echo "ALB and Target Group created successfully."
fi

# ---------------------------------------------------------------------------
# Prepare task definition (replace placeholders)
# ---------------------------------------------------------------------------
echo ""
echo "Preparing task definition..."
cp "$TASK_DEF_FILE" /tmp/task-definition-deploy.json

sed -i "s|{{ACCOUNT_ID}}|${ACCOUNT_ID}|g"   /tmp/task-definition-deploy.json
sed -i "s|{{AWS_REGION}}|${AWS_REGION}|g"   /tmp/task-definition-deploy.json
sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"     /tmp/task-definition-deploy.json

# ---------------------------------------------------------------------------
# Register task definition
# ---------------------------------------------------------------------------
echo "Registering task definition: $TASK_FAMILY"
TASK_DEF_ARN=$(aws ecs register-task-definition \
  --cli-input-json file:///tmp/task-definition-deploy.json \
  --region "$AWS_REGION" \
  --query "taskDefinition.taskDefinitionArn" \
  --output text)
echo "Registered task definition ARN: $TASK_DEF_ARN"

# ---------------------------------------------------------------------------
# Prepare service definition (replace placeholders)
# ---------------------------------------------------------------------------
echo ""
echo "Preparing service definition..."
cp "$SERVICE_DEF_FILE" /tmp/service-definition-deploy.json

sed -i "s|{{CLUSTER_NAME}}|${CLUSTER_NAME}|g"     /tmp/service-definition-deploy.json
sed -i "s|{{SUBNET_1}}|${SUBNET_1}|g"             /tmp/service-definition-deploy.json
sed -i "s|{{SUBNET_2}}|${SUBNET_2}|g"             /tmp/service-definition-deploy.json
sed -i "s|{{SECURITY_GROUP}}|${SECURITY_GROUP}|g" /tmp/service-definition-deploy.json

# Inject load balancer configuration if ALB was created
if [ -n "$TARGET_GROUP_ARN" ]; then
  # Add loadBalancers and healthCheckGracePeriodSeconds using Python (available on most systems)
  python3 - <<PYEOF
import json, sys

with open('/tmp/service-definition-deploy.json') as f:
    svc = json.load(f)

svc['loadBalancers'] = [{
    'targetGroupArn': '${TARGET_GROUP_ARN}',
    'containerName': '${PROJECT_NAME}',
    'containerPort': 8080
}]
svc['healthCheckGracePeriodSeconds'] = 300

with open('/tmp/service-definition-deploy.json', 'w') as f:
    json.dump(svc, f, indent=2)
PYEOF
fi

# ---------------------------------------------------------------------------
# Create or update ECS service
# ---------------------------------------------------------------------------
echo ""
EXISTING_SERVICE=$(aws ecs describe-services \
  --cluster "$CLUSTER_NAME" \
  --services "$SERVICE_NAME" \
  --region "$AWS_REGION" \
  --query "services[?status=='ACTIVE'].serviceName" \
  --output text 2>/dev/null || echo "")

if [ -z "$EXISTING_SERVICE" ] || [ "$EXISTING_SERVICE" = "None" ]; then
  echo "Creating new ECS service: $SERVICE_NAME"
  aws ecs create-service \
    --cli-input-json file:///tmp/service-definition-deploy.json \
    --region "$AWS_REGION"
else
  echo "Updating existing ECS service: $SERVICE_NAME"
  aws ecs update-service \
    --cluster "$CLUSTER_NAME" \
    --service "$SERVICE_NAME" \
    --task-definition "$TASK_DEF_ARN" \
    --region "$AWS_REGION"
fi

# ---------------------------------------------------------------------------
# Wait for stability
# ---------------------------------------------------------------------------
echo ""
echo "Waiting for service to stabilise (this may take a few minutes)..."
aws ecs wait services-stable \
  --cluster "$CLUSTER_NAME" \
  --services "$SERVICE_NAME" \
  --region "$AWS_REGION"

# ---------------------------------------------------------------------------
# Verify deployment
# ---------------------------------------------------------------------------
echo ""
echo "Deployment complete. Service details:"
aws ecs describe-services \
  --cluster "$CLUSTER_NAME" \
  --services "$SERVICE_NAME" \
  --region "$AWS_REGION" \
  --query "services[0].{Status:status,Running:runningCount,Desired:desiredCount,TaskDef:taskDefinition}" \
  --output table

echo ""
echo "CloudWatch Log Group : $LOG_GROUP"
if [ -n "$ALB_DNS" ]; then
  echo "Load Balancer DNS    : http://$ALB_DNS"
  echo "Health Check URL     : http://$ALB_DNS/actuator/health"
fi
echo ""
echo "Troubleshooting tips:"
echo "  - View logs  : aws logs tail $LOG_GROUP --follow --region $AWS_REGION"
echo "  - List tasks : aws ecs list-tasks --cluster $CLUSTER_NAME --service-name $SERVICE_NAME --region $AWS_REGION"
echo "  - Stop task  : aws ecs stop-task --cluster $CLUSTER_NAME --task <TASK_ARN> --region $AWS_REGION"
echo "=============================================="
echo "  Deployment finished successfully!"
echo "=============================================="
