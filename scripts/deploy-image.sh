#!/bin/bash

################################################################################
# AWS ECS Fargate Deployment Script for ResortsLite
# Deploys containerized application to AWS ECS Fargate
# Usage: ./deploy-image.sh
################################################################################

set -e  # Exit on error
set -o pipefail  # Exit on pipe failure

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo "=========================================="
echo "ResortsLite - AWS ECS Fargate Deployment"
echo "=========================================="
echo ""

# Configuration
PROJECT_NAME="resortslite"
TASK_FAMILY="${PROJECT_NAME}-task"
SERVICE_NAME="${PROJECT_NAME}-service"

# Prompt for AWS region
read -p "Enter AWS region (e.g., us-east-1): " AWS_REGION
if [ -z "$AWS_REGION" ]; then
    echo -e "${RED}Error: AWS region is required${NC}"
    exit 1
fi

# Get AWS Account ID
echo -e "${YELLOW}Getting AWS account ID...${NC}"
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
if [ -z "$ACCOUNT_ID" ]; then
    echo -e "${RED}Error: Failed to get AWS account ID${NC}"
    exit 1
fi
echo -e "${GREEN}AWS Account ID:${NC} $ACCOUNT_ID"
echo ""

# Prompt for ECS cluster name
read -p "Enter ECS cluster name (default: ${PROJECT_NAME}-cluster): " CLUSTER_NAME
CLUSTER_NAME=${CLUSTER_NAME:-${PROJECT_NAME}-cluster}

# Check if cluster exists, create if not
echo -e "${YELLOW}Checking if ECS cluster exists...${NC}"
aws ecs describe-clusters --clusters "$CLUSTER_NAME" --region "$AWS_REGION" >/dev/null 2>&1 || {
    echo -e "${YELLOW}Cluster does not exist. Creating ECS cluster: $CLUSTER_NAME${NC}"
    aws ecs create-cluster --cluster-name "$CLUSTER_NAME" --region "$AWS_REGION"
    echo -e "${GREEN}✓ ECS cluster created${NC}"
}
echo ""

# Prompt for VPC and network configuration
echo "=== Network Configuration ==="
read -p "Enter VPC ID: " VPC_ID
if [ -z "$VPC_ID" ]; then
    echo -e "${RED}Error: VPC ID is required${NC}"
    exit 1
fi

read -p "Enter subnet IDs (comma-separated, at least 2): " SUBNETS
if [ -z "$SUBNETS" ]; then
    echo -e "${RED}Error: At least 2 subnets are required for high availability${NC}"
    exit 1
fi

# Convert comma-separated subnets to array
IFS=',' read -ra SUBNET_ARRAY <<< "$SUBNETS"
SUBNET_1=$(echo "${SUBNET_ARRAY[0]}" | xargs)
SUBNET_2=$(echo "${SUBNET_ARRAY[1]}" | xargs)

read -p "Enter security group ID (must allow inbound on port 8080): " SECURITY_GROUP
if [ -z "$SECURITY_GROUP" ]; then
    echo -e "${RED}Error: Security group ID is required${NC}"
    exit 1
fi
echo ""

# Prompt for Docker image URI
echo "=== Docker Image Configuration ==="
read -p "Enter Docker image URI (e.g., 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
    echo -e "${RED}Error: Image URI is required${NC}"
    exit 1
fi
echo ""

# Prompt for database configuration
echo "=== Database Configuration ==="
read -p "Enter database URL (e.g., jdbc:oracle:thin:@host:1521:ORCL): " DB_URL
read -p "Enter database username: " DB_USERNAME
read -sp "Enter database password: " DB_PASSWORD
echo ""
echo ""

# Prompt for external service endpoints
echo "=== External Service Endpoints ==="
read -p "Enter payment service endpoint (default: http://payment-svc.internal:9090/charge): " PAYMENT_ENDPOINT
PAYMENT_ENDPOINT=${PAYMENT_ENDPOINT:-http://payment-svc.internal:9090/charge}

read -p "Enter inventory service endpoint (default: http://inventory-svc.internal:8081/rooms): " INVENTORY_ENDPOINT
INVENTORY_ENDPOINT=${INVENTORY_ENDPOINT:-http://inventory-svc.internal:8081/rooms}

read -p "Enter notification service endpoint (default: http://notify.internal:7070/send): " NOTIFICATION_ENDPOINT
NOTIFICATION_ENDPOINT=${NOTIFICATION_ENDPOINT:-http://notify.internal:7070/send}
echo ""

# Prompt for JWT secret
echo "=== JWT Configuration ==="
read -sp "Enter JWT secret (min 256 bits): " JWT_SECRET
echo ""
if [ -z "$JWT_SECRET" ]; then
    echo -e "${YELLOW}Warning: Using default JWT secret (not recommended for production)${NC}"
    JWT_SECRET="default-secret-key-for-development-only-min-256-bits-required-for-hs256-algorithm"
fi
echo ""

# Prompt for Memcached endpoint
echo "=== Memcached Configuration ==="
read -p "Enter Memcached endpoint (e.g., my-cluster.abc123.cfg.use1.cache.amazonaws.com:11211): " MEMCACHED_ENDPOINT
MEMCACHED_ENDPOINT=${MEMCACHED_ENDPOINT:-localhost:11211}
echo ""

# Load balancer configuration
echo "=== Load Balancer Configuration ==="
read -p "Do you need a load balancer for this service? (y/n): " NEED_LB

if [[ "$NEED_LB" =~ ^[Yy]$ ]]; then
    echo -e "${YELLOW}Creating Application Load Balancer and Target Group...${NC}"
    
    # Create target group with target-type ip (required for Fargate awsvpc mode)
    TG_NAME="${PROJECT_NAME}-tg"
    echo -e "${YELLOW}Creating target group: $TG_NAME${NC}"
    
    TARGET_GROUP_ARN=$(aws elbv2 create-target-group \
        --name "$TG_NAME" \
        --protocol HTTP \
        --port 8080 \
        --vpc-id "$VPC_ID" \
        --target-type ip \
        --health-check-enabled \
        --health-check-protocol HTTP \
        --health-check-path "/actuator/health" \
        --health-check-interval-seconds 30 \
        --health-check-timeout-seconds 5 \
        --healthy-threshold-count 2 \
        --unhealthy-threshold-count 3 \
        --region "$AWS_REGION" \
        --query 'TargetGroups[0].TargetGroupArn' \
        --output text 2>/dev/null || echo "")
    
    if [ -z "$TARGET_GROUP_ARN" ]; then
        # Target group might already exist, try to get it
        TARGET_GROUP_ARN=$(aws elbv2 describe-target-groups \
            --names "$TG_NAME" \
            --region "$AWS_REGION" \
            --query 'TargetGroups[0].TargetGroupArn' \
            --output text 2>/dev/null || echo "")
    fi
    
    if [ -z "$TARGET_GROUP_ARN" ]; then
        echo -e "${RED}Error: Failed to create or find target group${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}✓ Target Group ARN:${NC} $TARGET_GROUP_ARN"
    
    # Create Application Load Balancer
    ALB_NAME="${PROJECT_NAME}-alb"
    echo -e "${YELLOW}Creating Application Load Balancer: $ALB_NAME${NC}"
    
    ALB_ARN=$(aws elbv2 create-load-balancer \
        --name "$ALB_NAME" \
        --subnets "$SUBNET_1" "$SUBNET_2" \
        --security-groups "$SECURITY_GROUP" \
        --scheme internet-facing \
        --type application \
        --ip-address-type ipv4 \
        --region "$AWS_REGION" \
        --query 'LoadBalancers[0].LoadBalancerArn' \
        --output text 2>/dev/null || echo "")
    
    if [ -z "$ALB_ARN" ]; then
        # ALB might already exist, try to get it
        ALB_ARN=$(aws elbv2 describe-load-balancers \
            --names "$ALB_NAME" \
            --region "$AWS_REGION" \
            --query 'LoadBalancers[0].LoadBalancerArn' \
            --output text 2>/dev/null || echo "")
    fi
    
    if [ -z "$ALB_ARN" ]; then
        echo -e "${RED}Error: Failed to create or find load balancer${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}✓ Load Balancer ARN:${NC} $ALB_ARN"
    
    # Get ALB DNS name
    ALB_DNS=$(aws elbv2 describe-load-balancers \
        --load-balancer-arns "$ALB_ARN" \
        --region "$AWS_REGION" \
        --query 'LoadBalancers[0].DNSName' \
        --output text)
    
    echo -e "${GREEN}✓ Load Balancer DNS:${NC} $ALB_DNS"
    
    # Create listener
    echo -e "${YELLOW}Creating listener on port 80...${NC}"
    LISTENER_ARN=$(aws elbv2 create-listener \
        --load-balancer-arn "$ALB_ARN" \
        --protocol HTTP \
        --port 80 \
        --default-actions Type=forward,TargetGroupArn="$TARGET_GROUP_ARN" \
        --region "$AWS_REGION" \
        --query 'Listeners[0].ListenerArn' \
        --output text 2>/dev/null || echo "")
    
    if [ -z "$LISTENER_ARN" ]; then
        echo -e "${YELLOW}Listener might already exist${NC}"
    else
        echo -e "${GREEN}✓ Listener created${NC}"
    fi
    
    echo ""
else
    echo -e "${YELLOW}Skipping load balancer creation${NC}"
    TARGET_GROUP_ARN=""
    echo ""
fi

# Create CloudWatch log group
echo "=== CloudWatch Logs Configuration ==="
LOG_GROUP="/ecs/${PROJECT_NAME}"
echo -e "${YELLOW}Creating CloudWatch log group: $LOG_GROUP${NC}"
aws logs create-log-group --log-group-name "$LOG_GROUP" --region "$AWS_REGION" 2>/dev/null || echo -e "${YELLOW}Log group already exists${NC}"
echo ""

# Replace placeholders in task definition
echo "=== Preparing ECS Task Definition ==="
TASK_DEF_FILE="ecs/task-definition.json"
TASK_DEF_TEMP="ecs/task-definition-temp.json"

cp "$TASK_DEF_FILE" "$TASK_DEF_TEMP"

sed -i "s|{{ACCOUNT_ID}}|$ACCOUNT_ID|g" "$TASK_DEF_TEMP"
sed -i "s|{{AWS_REGION}}|$AWS_REGION|g" "$TASK_DEF_TEMP"
sed -i "s|{{IMAGE_URI}}|$IMAGE_URI|g" "$TASK_DEF_TEMP"
sed -i "s|{{DB_URL}}|$DB_URL|g" "$TASK_DEF_TEMP"
sed -i "s|{{DB_USERNAME}}|$DB_USERNAME|g" "$TASK_DEF_TEMP"
sed -i "s|{{DB_PASSWORD}}|$DB_PASSWORD|g" "$TASK_DEF_TEMP"
sed -i "s|{{PAYMENT_ENDPOINT}}|$PAYMENT_ENDPOINT|g" "$TASK_DEF_TEMP"
sed -i "s|{{INVENTORY_ENDPOINT}}|$INVENTORY_ENDPOINT|g" "$TASK_DEF_TEMP"
sed -i "s|{{NOTIFICATION_ENDPOINT}}|$NOTIFICATION_ENDPOINT|g" "$TASK_DEF_TEMP"
sed -i "s|{{JWT_SECRET}}|$JWT_SECRET|g" "$TASK_DEF_TEMP"
sed -i "s|{{MEMCACHED_ENDPOINT}}|$MEMCACHED_ENDPOINT|g" "$TASK_DEF_TEMP"

echo -e "${GREEN}✓ Task definition prepared${NC}"
echo ""

# Register task definition
echo "=== Registering ECS Task Definition ==="
TASK_DEF_ARN=$(aws ecs register-task-definition \
    --cli-input-json file://"$TASK_DEF_TEMP" \
    --region "$AWS_REGION" \
    --query 'taskDefinition.taskDefinitionArn' \
    --output text)

if [ -z "$TASK_DEF_ARN" ]; then
    echo -e "${RED}Error: Failed to register task definition${NC}"
    rm -f "$TASK_DEF_TEMP"
    exit 1
fi

echo -e "${GREEN}✓ Task definition registered:${NC} $TASK_DEF_ARN"
rm -f "$TASK_DEF_TEMP"
echo ""

# Prepare service definition
echo "=== Preparing ECS Service Definition ==="
SERVICE_DEF_FILE="ecs/service-definition.json"
SERVICE_DEF_TEMP="ecs/service-definition-temp.json"

cp "$SERVICE_DEF_FILE" "$SERVICE_DEF_TEMP"

sed -i "s|{{CLUSTER_NAME}}|$CLUSTER_NAME|g" "$SERVICE_DEF_TEMP"
sed -i "s|{{SUBNET_1}}|$SUBNET_1|g" "$SERVICE_DEF_TEMP"
sed -i "s|{{SUBNET_2}}|$SUBNET_2|g" "$SERVICE_DEF_TEMP"
sed -i "s|{{SECURITY_GROUP}}|$SECURITY_GROUP|g" "$SERVICE_DEF_TEMP"

if [ -z "$TARGET_GROUP_ARN" ]; then
    # Remove loadBalancers section if no load balancer
    jq 'del(.loadBalancers) | del(.healthCheckGracePeriodSeconds)' "$SERVICE_DEF_TEMP" > "${SERVICE_DEF_TEMP}.tmp"
    mv "${SERVICE_DEF_TEMP}.tmp" "$SERVICE_DEF_TEMP"
else
    sed -i "s|{{TARGET_GROUP_ARN}}|$TARGET_GROUP_ARN|g" "$SERVICE_DEF_TEMP"
fi

echo -e "${GREEN}✓ Service definition prepared${NC}"
echo ""

# Check if service exists
echo "=== Checking ECS Service Status ==="
EXISTING_SERVICE=$(aws ecs describe-services \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION" \
    --query 'services[?status==`ACTIVE`].serviceName' \
    --output text 2>/dev/null || echo "")

if [ -z "$EXISTING_SERVICE" ] || [ "$EXISTING_SERVICE" == "None" ]; then
    # Create new service
    echo -e "${YELLOW}Creating new ECS service: $SERVICE_NAME${NC}"
    aws ecs create-service \
        --cli-input-json file://"$SERVICE_DEF_TEMP" \
        --region "$AWS_REGION" >/dev/null
    
    if [ $? -ne 0 ]; then
        echo -e "${RED}Error: Failed to create service${NC}"
        rm -f "$SERVICE_DEF_TEMP"
        exit 1
    fi
    
    echo -e "${GREEN}✓ Service created${NC}"
else
    # Update existing service
    echo -e "${YELLOW}Updating existing ECS service: $SERVICE_NAME${NC}"
    aws ecs update-service \
        --cluster "$CLUSTER_NAME" \
        --service "$SERVICE_NAME" \
        --task-definition "$TASK_DEF_ARN" \
        --desired-count 2 \
        --region "$AWS_REGION" >/dev/null
    
    if [ $? -ne 0 ]; then
        echo -e "${RED}Error: Failed to update service${NC}"
        rm -f "$SERVICE_DEF_TEMP"
        exit 1
    fi
    
    echo -e "${GREEN}✓ Service updated${NC}"
fi

rm -f "$SERVICE_DEF_TEMP"
echo ""

# Wait for service to stabilize
echo "=== Waiting for Service Stability ==="
echo -e "${YELLOW}This may take a few minutes...${NC}"
aws ecs wait services-stable \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION"

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Service is stable${NC}"
else
    echo -e "${YELLOW}Warning: Service stability check timed out${NC}"
fi
echo ""

# Verify deployment
echo "=== Deployment Verification ==="
SERVICE_INFO=$(aws ecs describe-services \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION" \
    --query 'services[0].[runningCount,desiredCount,status]' \
    --output text)

echo -e "${GREEN}Service Status:${NC} $SERVICE_INFO"
echo ""

# Display summary
echo "=========================================="
echo "Deployment Complete"
echo "=========================================="
echo -e "${GREEN}Cluster:${NC} $CLUSTER_NAME"
echo -e "${GREEN}Service:${NC} $SERVICE_NAME"
echo -e "${GREEN}Task Definition:${NC} $TASK_DEF_ARN"
echo -e "${GREEN}Region:${NC} $AWS_REGION"
echo -e "${GREEN}CloudWatch Logs:${NC} $LOG_GROUP"

if [ -n "$ALB_DNS" ]; then
    echo -e "${GREEN}Application URL:${NC} http://$ALB_DNS"
    echo -e "${GREEN}Health Check:${NC} http://$ALB_DNS/actuator/health"
fi

echo ""
echo "Next steps:"
echo "1. Monitor service: aws ecs describe-services --cluster $CLUSTER_NAME --services $SERVICE_NAME --region $AWS_REGION"
echo "2. View logs: aws logs tail $LOG_GROUP --follow --region $AWS_REGION"
echo "3. Check tasks: aws ecs list-tasks --cluster $CLUSTER_NAME --service-name $SERVICE_NAME --region $AWS_REGION"
echo ""
