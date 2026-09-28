@echo off
setlocal enabledelayedexpansion

REM ################################################################################
REM AWS ECS Fargate Deployment Script for ResortsLite (Windows)
REM Deploys containerized application to AWS ECS Fargate
REM Usage: deploy-image.bat
REM ################################################################################

echo ==========================================
echo ResortsLite - AWS ECS Fargate Deployment
echo ==========================================
echo.

REM Configuration
set PROJECT_NAME=resortslite
set TASK_FAMILY=%PROJECT_NAME%-task
set SERVICE_NAME=%PROJECT_NAME%-service

REM Prompt for AWS region
set /p AWS_REGION="Enter AWS region (e.g., us-east-1): "
if "!AWS_REGION!"=="" (
    echo Error: AWS region is required
    exit /b 1
)

REM Get AWS Account ID
echo Getting AWS account ID...
for /f "tokens=*" %%a in ('aws sts get-caller-identity --query Account --output text') do set ACCOUNT_ID=%%a
if "!ACCOUNT_ID!"=="" (
    echo Error: Failed to get AWS account ID
    exit /b 1
)
echo AWS Account ID: !ACCOUNT_ID!
echo.

REM Prompt for ECS cluster name
set /p CLUSTER_NAME="Enter ECS cluster name (default: %PROJECT_NAME%-cluster): "
if "!CLUSTER_NAME!"=="" set CLUSTER_NAME=%PROJECT_NAME%-cluster

REM Check if cluster exists, create if not
echo Checking if ECS cluster exists...
aws ecs describe-clusters --clusters !CLUSTER_NAME! --region !AWS_REGION! >nul 2>&1
if !ERRORLEVEL! neq 0 (
    echo Cluster does not exist. Creating ECS cluster: !CLUSTER_NAME!
    aws ecs create-cluster --cluster-name !CLUSTER_NAME! --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
        echo Error: Failed to create cluster
        exit /b 1
    )
    echo ECS cluster created
)
echo.

REM Prompt for VPC and network configuration
echo === Network Configuration ===
set /p VPC_ID="Enter VPC ID: "
if "!VPC_ID!"=="" (
    echo Error: VPC ID is required
    exit /b 1
)

set /p SUBNETS="Enter subnet IDs (comma-separated, at least 2): "
if "!SUBNETS!"=="" (
    echo Error: At least 2 subnets are required for high availability
    exit /b 1
)

REM Parse subnets
for /f "tokens=1,2 delims=," %%a in ("!SUBNETS!") do (
    set SUBNET_1=%%a
    set SUBNET_2=%%b
)
set SUBNET_1=!SUBNET_1: =!
set SUBNET_2=!SUBNET_2: =!

set /p SECURITY_GROUP="Enter security group ID (must allow inbound on port 8080): "
if "!SECURITY_GROUP!"=="" (
    echo Error: Security group ID is required
    exit /b 1
)
echo.

REM Prompt for Docker image URI
echo === Docker Image Configuration ===
set /p IMAGE_URI="Enter Docker image URI (e.g., 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo Error: Image URI is required
    exit /b 1
)
echo.

REM Prompt for database configuration
echo === Database Configuration ===
set /p DB_URL="Enter database URL (e.g., jdbc:oracle:thin:@host:1521:ORCL): "
set /p DB_USERNAME="Enter database username: "
set /p DB_PASSWORD="Enter database password: "
echo.

REM Prompt for external service endpoints
echo === External Service Endpoints ===
set /p PAYMENT_ENDPOINT="Enter payment service endpoint (default: http://payment-svc.internal:9090/charge): "
if "!PAYMENT_ENDPOINT!"=="" set PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge

set /p INVENTORY_ENDPOINT="Enter inventory service endpoint (default: http://inventory-svc.internal:8081/rooms): "
if "!INVENTORY_ENDPOINT!"=="" set INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms

set /p NOTIFICATION_ENDPOINT="Enter notification service endpoint (default: http://notify.internal:7070/send): "
if "!NOTIFICATION_ENDPOINT!"=="" set NOTIFICATION_ENDPOINT=http://notify.internal:7070/send
echo.

REM Prompt for JWT secret
echo === JWT Configuration ===
set /p JWT_SECRET="Enter JWT secret (min 256 bits): "
if "!JWT_SECRET!"=="" (
    echo Warning: Using default JWT secret (not recommended for production)
    set JWT_SECRET=default-secret-key-for-development-only-min-256-bits-required-for-hs256-algorithm
)
echo.

REM Prompt for Memcached endpoint
echo === Memcached Configuration ===
set /p MEMCACHED_ENDPOINT="Enter Memcached endpoint (e.g., my-cluster.abc123.cfg.use1.cache.amazonaws.com:11211): "
if "!MEMCACHED_ENDPOINT!"=="" set MEMCACHED_ENDPOINT=localhost:11211
echo.

REM Load balancer configuration
echo === Load Balancer Configuration ===
set /p NEED_LB="Do you need a load balancer for this service? (y/n): "

if /i "!NEED_LB!"=="y" (
    echo Creating Application Load Balancer and Target Group...
    
    REM Create target group
    set TG_NAME=%PROJECT_NAME%-tg
    echo Creating target group: !TG_NAME!
    
    for /f "tokens=*" %%a in ('aws elbv2 create-target-group --name !TG_NAME! --protocol HTTP --port 8080 --vpc-id !VPC_ID! --target-type ip --health-check-enabled --health-check-protocol HTTP --health-check-path "/actuator/health" --health-check-interval-seconds 30 --health-check-timeout-seconds 5 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region !AWS_REGION! --query "TargetGroups[0].TargetGroupArn" --output text 2^>nul') do set TARGET_GROUP_ARN=%%a
    
    if "!TARGET_GROUP_ARN!"=="" (
        REM Target group might already exist
        for /f "tokens=*" %%a in ('aws elbv2 describe-target-groups --names !TG_NAME! --region !AWS_REGION! --query "TargetGroups[0].TargetGroupArn" --output text 2^>nul') do set TARGET_GROUP_ARN=%%a
    )
    
    if "!TARGET_GROUP_ARN!"=="" (
        echo Error: Failed to create or find target group
        exit /b 1
    )
    
    echo Target Group ARN: !TARGET_GROUP_ARN!
    
    REM Create Application Load Balancer
    set ALB_NAME=%PROJECT_NAME%-alb
    echo Creating Application Load Balancer: !ALB_NAME!
    
    for /f "tokens=*" %%a in ('aws elbv2 create-load-balancer --name !ALB_NAME! --subnets !SUBNET_1! !SUBNET_2! --security-groups !SECURITY_GROUP! --scheme internet-facing --type application --ip-address-type ipv4 --region !AWS_REGION! --query "LoadBalancers[0].LoadBalancerArn" --output text 2^>nul') do set ALB_ARN=%%a
    
    if "!ALB_ARN!"=="" (
        REM ALB might already exist
        for /f "tokens=*" %%a in ('aws elbv2 describe-load-balancers --names !ALB_NAME! --region !AWS_REGION! --query "LoadBalancers[0].LoadBalancerArn" --output text 2^>nul') do set ALB_ARN=%%a
    )
    
    if "!ALB_ARN!"=="" (
        echo Error: Failed to create or find load balancer
        exit /b 1
    )
    
    echo Load Balancer ARN: !ALB_ARN!
    
    REM Get ALB DNS name
    for /f "tokens=*" %%a in ('aws elbv2 describe-load-balancers --load-balancer-arns !ALB_ARN! --region !AWS_REGION! --query "LoadBalancers[0].DNSName" --output text') do set ALB_DNS=%%a
    echo Load Balancer DNS: !ALB_DNS!
    
    REM Create listener
    echo Creating listener on port 80...
    aws elbv2 create-listener --load-balancer-arn !ALB_ARN! --protocol HTTP --port 80 --default-actions Type=forward,TargetGroupArn=!TARGET_GROUP_ARN! --region !AWS_REGION! >nul 2>&1
    echo Listener created
    echo.
) else (
    echo Skipping load balancer creation
    set TARGET_GROUP_ARN=
    echo.
)

REM Create CloudWatch log group
echo === CloudWatch Logs Configuration ===
set LOG_GROUP=/ecs/%PROJECT_NAME%
echo Creating CloudWatch log group: !LOG_GROUP!
aws logs create-log-group --log-group-name !LOG_GROUP! --region !AWS_REGION! 2>nul
echo.

REM Replace placeholders in task definition
echo === Preparing ECS Task Definition ===
set TASK_DEF_FILE=ecs\task-definition.json
set TASK_DEF_TEMP=ecs\task-definition-temp.json

copy !TASK_DEF_FILE! !TASK_DEF_TEMP! >nul

powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{ACCOUNT_ID}}', '!ACCOUNT_ID!' | Set-Content !TASK_DEF_TEMP!"
powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{AWS_REGION}}', '!AWS_REGION!' | Set-Content !TASK_DEF_TEMP!"
powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content !TASK_DEF_TEMP!"
powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{DB_URL}}', '!DB_URL!' | Set-Content !TASK_DEF_TEMP!"
powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{DB_USERNAME}}', '!DB_USERNAME!' | Set-Content !TASK_DEF_TEMP!"
powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{DB_PASSWORD}}', '!DB_PASSWORD!' | Set-Content !TASK_DEF_TEMP!"
powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{PAYMENT_ENDPOINT}}', '!PAYMENT_ENDPOINT!' | Set-Content !TASK_DEF_TEMP!"
powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{INVENTORY_ENDPOINT}}', '!INVENTORY_ENDPOINT!' | Set-Content !TASK_DEF_TEMP!"
powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{NOTIFICATION_ENDPOINT}}', '!NOTIFICATION_ENDPOINT!' | Set-Content !TASK_DEF_TEMP!"
powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{JWT_SECRET}}', '!JWT_SECRET!' | Set-Content !TASK_DEF_TEMP!"
powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{MEMCACHED_ENDPOINT}}', '!MEMCACHED_ENDPOINT!' | Set-Content !TASK_DEF_TEMP!"

echo Task definition prepared
echo.

REM Register task definition
echo === Registering ECS Task Definition ===
for /f "tokens=*" %%a in ('aws ecs register-task-definition --cli-input-json file://!TASK_DEF_TEMP! --region !AWS_REGION! --query "taskDefinition.taskDefinitionArn" --output text') do set TASK_DEF_ARN=%%a

if "!TASK_DEF_ARN!"=="" (
    echo Error: Failed to register task definition
    del !TASK_DEF_TEMP!
    exit /b 1
)

echo Task definition registered: !TASK_DEF_ARN!
del !TASK_DEF_TEMP!
echo.

REM Prepare service definition
echo === Preparing ECS Service Definition ===
set SERVICE_DEF_FILE=ecs\service-definition.json
set SERVICE_DEF_TEMP=ecs\service-definition-temp.json

copy !SERVICE_DEF_FILE! !SERVICE_DEF_TEMP! >nul

powershell -Command "(Get-Content !SERVICE_DEF_TEMP!) -replace '{{CLUSTER_NAME}}', '!CLUSTER_NAME!' | Set-Content !SERVICE_DEF_TEMP!"
powershell -Command "(Get-Content !SERVICE_DEF_TEMP!) -replace '{{SUBNET_1}}', '!SUBNET_1!' | Set-Content !SERVICE_DEF_TEMP!"
powershell -Command "(Get-Content !SERVICE_DEF_TEMP!) -replace '{{SUBNET_2}}', '!SUBNET_2!' | Set-Content !SERVICE_DEF_TEMP!"
powershell -Command "(Get-Content !SERVICE_DEF_TEMP!) -replace '{{SECURITY_GROUP}}', '!SECURITY_GROUP!' | Set-Content !SERVICE_DEF_TEMP!"

if "!TARGET_GROUP_ARN!"=="" (
    REM Remove loadBalancers section if no load balancer
    powershell -Command "$json = Get-Content !SERVICE_DEF_TEMP! | ConvertFrom-Json; $json.PSObject.Properties.Remove('loadBalancers'); $json.PSObject.Properties.Remove('healthCheckGracePeriodSeconds'); $json | ConvertTo-Json -Depth 10 | Set-Content !SERVICE_DEF_TEMP!"
) else (
    powershell -Command "(Get-Content !SERVICE_DEF_TEMP!) -replace '{{TARGET_GROUP_ARN}}', '!TARGET_GROUP_ARN!' | Set-Content !SERVICE_DEF_TEMP!"
)

echo Service definition prepared
echo.

REM Check if service exists
echo === Checking ECS Service Status ===
for /f "tokens=*" %%a in ('aws ecs describe-services --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION! --query "services[?status==`ACTIVE`].serviceName" --output text 2^>nul') do set EXISTING_SERVICE=%%a

if "!EXISTING_SERVICE!"=="" (
    REM Create new service
    echo Creating new ECS service: !SERVICE_NAME!
    aws ecs create-service --cli-input-json file://!SERVICE_DEF_TEMP! --region !AWS_REGION! >nul
    if !ERRORLEVEL! neq 0 (
        echo Error: Failed to create service
        del !SERVICE_DEF_TEMP!
        exit /b 1
    )
    echo Service created
) else (
    REM Update existing service
    echo Updating existing ECS service: !SERVICE_NAME!
    aws ecs update-service --cluster !CLUSTER_NAME! --service !SERVICE_NAME! --task-definition !TASK_DEF_ARN! --desired-count 2 --region !AWS_REGION! >nul
    if !ERRORLEVEL! neq 0 (
        echo Error: Failed to update service
        del !SERVICE_DEF_TEMP!
        exit /b 1
    )
    echo Service updated
)

del !SERVICE_DEF_TEMP!
echo.

REM Wait for service to stabilize
echo === Waiting for Service Stability ===
echo This may take a few minutes...
aws ecs wait services-stable --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION!
if !ERRORLEVEL! equ 0 (
    echo Service is stable
) else (
    echo Warning: Service stability check timed out
)
echo.

REM Verify deployment
echo === Deployment Verification ===
for /f "tokens=*" %%a in ('aws ecs describe-services --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION! --query "services[0].[runningCount,desiredCount,status]" --output text') do set SERVICE_INFO=%%a
echo Service Status: !SERVICE_INFO!
echo.

REM Display summary
echo ==========================================
echo Deployment Complete
echo ==========================================
echo Cluster: !CLUSTER_NAME!
echo Service: !SERVICE_NAME!
echo Task Definition: !TASK_DEF_ARN!
echo Region: !AWS_REGION!
echo CloudWatch Logs: !LOG_GROUP!

if not "!ALB_DNS!"=="" (
    echo Application URL: http://!ALB_DNS!
    echo Health Check: http://!ALB_DNS!/actuator/health
)

echo.
echo Next steps:
echo 1. Monitor service: aws ecs describe-services --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION!
echo 2. View logs: aws logs tail !LOG_GROUP! --follow --region !AWS_REGION!
echo 3. Check tasks: aws ecs list-tasks --cluster !CLUSTER_NAME! --service-name !SERVICE_NAME! --region !AWS_REGION!
echo.

endlocal
