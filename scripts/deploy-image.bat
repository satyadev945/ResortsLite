@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: deploy-image.bat — Deploy ResortsLite to AWS ECS Fargate (Windows)
:: Usage: scripts\deploy-image.bat   (run from repository root)
:: =============================================================================

set "PROJECT_NAME=resortsLite"
set "SERVICE_NAME=resortsLite-service"
set "TASK_FAMILY=resortsLite-task"
set "LOG_GROUP=/ecs/resortsLite"
set "TASK_DEF_FILE=ecs\task-definition.json"
set "SERVICE_DEF_FILE=ecs\service-definition.json"

echo ==============================================
echo   ResortsLite -- ECS Fargate Deployment
echo ==============================================
echo.

:: ---------------------------------------------------------------------------
:: Collect configuration
:: ---------------------------------------------------------------------------
set /p "AWS_REGION=AWS Region [us-east-1]: "
if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

set /p "CLUSTER_NAME=ECS Cluster name [resortsLite-cluster]: "
if "!CLUSTER_NAME!"=="" set "CLUSTER_NAME=resortsLite-cluster"

set /p "IMAGE_URI=ECR Image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortsLite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Image URI is required.
    exit /b 1
)

echo.
echo --- Network Configuration ---
set /p "VPC_ID=VPC ID: "
set /p "SUBNET_1=Subnet 1 ID: "
set /p "SUBNET_2=Subnet 2 ID: "
set /p "SECURITY_GROUP=Security Group ID: "

if "!SUBNET_1!"=="" (
    echo ERROR: Subnet 1 is required.
    exit /b 1
)
if "!SUBNET_2!"=="" (
    echo ERROR: Subnet 2 is required.
    exit /b 1
)
if "!SECURITY_GROUP!"=="" (
    echo ERROR: Security Group is required.
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: Resolve AWS Account ID
:: ---------------------------------------------------------------------------
echo.
echo Resolving AWS Account ID...
for /f "delims=" %%a in ('aws sts get-caller-identity --query Account --output text') do set "ACCOUNT_ID=%%a"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to get AWS Account ID. Check your AWS credentials.
    exit /b 1
)
echo Account ID: !ACCOUNT_ID!

:: ---------------------------------------------------------------------------
:: Ensure CloudWatch log group exists
:: ---------------------------------------------------------------------------
echo.
echo Ensuring CloudWatch log group exists: !LOG_GROUP!
aws logs create-log-group --log-group-name "!LOG_GROUP!" --region "!AWS_REGION!" 2>nul
echo Log group ready.

:: ---------------------------------------------------------------------------
:: Ensure ECS cluster exists
:: ---------------------------------------------------------------------------
echo.
echo Checking ECS cluster: !CLUSTER_NAME!
for /f "delims=" %%s in ('aws ecs describe-clusters --clusters "!CLUSTER_NAME!" --region "!AWS_REGION!" --query "clusters[0].status" --output text 2^>nul') do set "CLUSTER_STATUS=%%s"
if "!CLUSTER_STATUS!" neq "ACTIVE" (
    echo Creating ECS cluster: !CLUSTER_NAME!
    aws ecs create-cluster --cluster-name "!CLUSTER_NAME!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS cluster.
        exit /b 1
    )
) else (
    echo Cluster already exists and is ACTIVE.
)

:: ---------------------------------------------------------------------------
:: Load Balancer (optional)
:: ---------------------------------------------------------------------------
echo.
set /p "NEED_ALB=Do you need an Application Load Balancer for this service? (y/n) [n]: "
if "!NEED_ALB!"=="" set "NEED_ALB=n"

set "TARGET_GROUP_ARN="
set "ALB_DNS="

if /i "!NEED_ALB!"=="y" (
    echo.
    echo Creating Application Load Balancer...

    set "ALB_NAME=!PROJECT_NAME!-alb"
    set "TG_NAME=!PROJECT_NAME!-tg"

    for /f "delims=" %%a in ('aws elbv2 create-load-balancer --name "!ALB_NAME!" --subnets "!SUBNET_1!" "!SUBNET_2!" --security-groups "!SECURITY_GROUP!" --scheme internet-facing --type application --region "!AWS_REGION!" --query "LoadBalancers[0].LoadBalancerArn" --output text') do set "ALB_ARN=%%a"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ALB.
        exit /b 1
    )
    echo ALB ARN: !ALB_ARN!

    for /f "delims=" %%d in ('aws elbv2 describe-load-balancers --load-balancer-arns "!ALB_ARN!" --region "!AWS_REGION!" --query "LoadBalancers[0].DNSName" --output text') do set "ALB_DNS=%%d"

    for /f "delims=" %%t in ('aws elbv2 create-target-group --name "!TG_NAME!" --protocol HTTP --port 8080 --vpc-id "!VPC_ID!" --target-type ip --health-check-path "/actuator/health" --health-check-interval-seconds 30 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region "!AWS_REGION!" --query "TargetGroups[0].TargetGroupArn" --output text') do set "TARGET_GROUP_ARN=%%t"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create Target Group.
        exit /b 1
    )
    echo Target Group ARN: !TARGET_GROUP_ARN!

    aws elbv2 create-listener --load-balancer-arn "!ALB_ARN!" --protocol HTTP --port 80 --default-actions "Type=forward,TargetGroupArn=!TARGET_GROUP_ARN!" --region "!AWS_REGION!" >nul
    echo ALB and Target Group created successfully.
)

:: ---------------------------------------------------------------------------
:: Prepare task definition (replace placeholders using PowerShell)
:: ---------------------------------------------------------------------------
echo.
echo Preparing task definition...
copy /Y "!TASK_DEF_FILE!" "%TEMP%\task-definition-deploy.json" >nul

powershell -NoProfile -Command ^
  "(Get-Content '%TEMP%\task-definition-deploy.json') -replace '{{ACCOUNT_ID}}','!ACCOUNT_ID!' -replace '{{AWS_REGION}}','!AWS_REGION!' -replace '{{IMAGE_URI}}','!IMAGE_URI!' | Set-Content '%TEMP%\task-definition-deploy.json'"

:: ---------------------------------------------------------------------------
:: Register task definition
:: ---------------------------------------------------------------------------
echo Registering task definition: !TASK_FAMILY!
for /f "delims=" %%a in ('aws ecs register-task-definition --cli-input-json "file://%TEMP%\task-definition-deploy.json" --region "!AWS_REGION!" --query "taskDefinition.taskDefinitionArn" --output text') do set "TASK_DEF_ARN=%%a"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to register task definition.
    exit /b 1
)
echo Registered task definition ARN: !TASK_DEF_ARN!

:: ---------------------------------------------------------------------------
:: Prepare service definition (replace placeholders)
:: ---------------------------------------------------------------------------
echo.
echo Preparing service definition...
copy /Y "!SERVICE_DEF_FILE!" "%TEMP%\service-definition-deploy.json" >nul

powershell -NoProfile -Command ^
  "(Get-Content '%TEMP%\service-definition-deploy.json') -replace '{{CLUSTER_NAME}}','!CLUSTER_NAME!' -replace '{{SUBNET_1}}','!SUBNET_1!' -replace '{{SUBNET_2}}','!SUBNET_2!' -replace '{{SECURITY_GROUP}}','!SECURITY_GROUP!' | Set-Content '%TEMP%\service-definition-deploy.json'"

:: Add load balancer config if ALB was created
if "!TARGET_GROUP_ARN!" neq "" (
    powershell -NoProfile -Command ^
      "$svc = Get-Content '%TEMP%\service-definition-deploy.json' | ConvertFrom-Json; $svc | Add-Member -Force -NotePropertyName 'loadBalancers' -NotePropertyValue @(@{targetGroupArn='!TARGET_GROUP_ARN!';containerName='resortsLite';containerPort=8080}); $svc | Add-Member -Force -NotePropertyName 'healthCheckGracePeriodSeconds' -NotePropertyValue 300; $svc | ConvertTo-Json -Depth 10 | Set-Content '%TEMP%\service-definition-deploy.json'"
)

:: ---------------------------------------------------------------------------
:: Create or update ECS service
:: ---------------------------------------------------------------------------
echo.
for /f "delims=" %%s in ('aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[?status==''ACTIVE''].serviceName" --output text 2^>nul') do set "EXISTING_SERVICE=%%s"

if "!EXISTING_SERVICE!"=="" (
    echo Creating new ECS service: !SERVICE_NAME!
    aws ecs create-service --cli-input-json "file://%TEMP%\service-definition-deploy.json" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS service.
        exit /b 1
    )
) else (
    echo Updating existing ECS service: !SERVICE_NAME!
    aws ecs update-service --cluster "!CLUSTER_NAME!" --service "!SERVICE_NAME!" --task-definition "!TASK_DEF_ARN!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to update ECS service.
        exit /b 1
    )
)

:: ---------------------------------------------------------------------------
:: Wait for stability
:: ---------------------------------------------------------------------------
echo.
echo Waiting for service to stabilise (this may take a few minutes)...
aws ecs wait services-stable --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!"
if !ERRORLEVEL! neq 0 (
    echo WARNING: Service did not stabilise within the expected time. Check ECS console.
)

:: ---------------------------------------------------------------------------
:: Verify deployment
:: ---------------------------------------------------------------------------
echo.
echo Deployment complete. Service details:
aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[0].{Status:status,Running:runningCount,Desired:desiredCount}" --output table

echo.
echo CloudWatch Log Group : !LOG_GROUP!
if "!ALB_DNS!" neq "" (
    echo Load Balancer DNS    : http://!ALB_DNS!
    echo Health Check URL     : http://!ALB_DNS!/actuator/health
)
echo.
echo Troubleshooting tips:
echo   - View logs  : aws logs tail !LOG_GROUP! --follow --region !AWS_REGION!
echo   - List tasks : aws ecs list-tasks --cluster !CLUSTER_NAME! --service-name !SERVICE_NAME! --region !AWS_REGION!
echo.
echo ==============================================
echo   Deployment finished successfully!
echo ==============================================

endlocal
