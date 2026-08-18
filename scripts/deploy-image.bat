@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: deploy-image.bat — Deploy ResortsLite to AWS ECS Fargate (Windows)
:: Usage: scripts\deploy-image.bat   (run from repository root)
:: =============================================================================

set "SERVICE_NAME=resortsLite-service"
set "TASK_FAMILY=resortsLite-task"
set "CONTAINER_NAME=resortsLite"
set "APP_PORT=8080"
set "LOG_GROUP=/ecs/resortsLite"
set "TASK_DEF_FILE=ecs\task-definition.json"
set "SVC_DEF_FILE=ecs\service-definition.json"

echo ==============================================
echo   ResortsLite -- ECS Fargate Deployment
echo ==============================================
echo.

:: ---------------------------------------------------------------------------
:: Collect inputs
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

set /p "VPC_ID=VPC ID (e.g. vpc-xxxxxxxx): "
set /p "SUBNETS_RAW=Subnet IDs comma-separated at least 2 (e.g. subnet-aaa,subnet-bbb): "
set /p "SECURITY_GROUP=Security Group ID (e.g. sg-xxxxxxxx): "

:: Parse subnets
for /f "tokens=1,2 delims=," %%A in ("!SUBNETS_RAW!") do (
    set "SUBNET_1=%%A"
    set "SUBNET_2=%%B"
)
set "SUBNET_1=!SUBNET_1: =!"
set "SUBNET_2=!SUBNET_2: =!"
if "!SUBNET_2!"=="" set "SUBNET_2=!SUBNET_1!"

:: ---------------------------------------------------------------------------
:: Derive AWS Account ID
:: ---------------------------------------------------------------------------
echo.
echo Fetching AWS Account ID...
for /f "delims=" %%A in ('aws sts get-caller-identity --query Account --output text') do set "ACCOUNT_ID=%%A"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to get AWS Account ID. Check your AWS credentials.
    exit /b 1
)
echo Account ID: !ACCOUNT_ID!

:: ---------------------------------------------------------------------------
:: Ensure CloudWatch log group exists
:: ---------------------------------------------------------------------------
echo Ensuring CloudWatch log group '!LOG_GROUP!' exists...
aws logs create-log-group --log-group-name "!LOG_GROUP!" --region "!AWS_REGION!" >nul 2>&1

:: ---------------------------------------------------------------------------
:: Ensure ECS cluster exists
:: ---------------------------------------------------------------------------
echo Checking ECS cluster '!CLUSTER_NAME!'...
for /f "delims=" %%S in ('aws ecs describe-clusters --clusters "!CLUSTER_NAME!" --region "!AWS_REGION!" --query "clusters[0].status" --output text 2^>nul') do set "CLUSTER_STATUS=%%S"
if not "!CLUSTER_STATUS!"=="ACTIVE" (
    echo Creating ECS cluster '!CLUSTER_NAME!'...
    aws ecs create-cluster --cluster-name "!CLUSTER_NAME!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS cluster.
        exit /b 1
    )
)
echo Cluster ready.

:: ---------------------------------------------------------------------------
:: Load balancer (optional)
:: ---------------------------------------------------------------------------
echo.
set /p "NEED_LB=Do you need an Application Load Balancer for this service? (y/n) [n]: "
if "!NEED_LB!"=="" set "NEED_LB=n"

set "TARGET_GROUP_ARN="
set "LB_DNS="

if /i "!NEED_LB!"=="y" (
    echo.
    echo Creating Application Load Balancer...

    set "LB_NAME=resortsLite-alb"
    set "TG_NAME=resortsLite-tg"

    for /f "delims=" %%A in ('aws elbv2 create-load-balancer --name "!LB_NAME!" --subnets "!SUBNET_1!" "!SUBNET_2!" --security-groups "!SECURITY_GROUP!" --scheme internet-facing --type application --region "!AWS_REGION!" --query "LoadBalancers[0].LoadBalancerArn" --output text') do set "LB_ARN=%%A"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ALB.
        exit /b 1
    )
    echo ALB ARN: !LB_ARN!

    for /f "delims=" %%D in ('aws elbv2 describe-load-balancers --load-balancer-arns "!LB_ARN!" --region "!AWS_REGION!" --query "LoadBalancers[0].DNSName" --output text') do set "LB_DNS=%%D"

    for /f "delims=" %%T in ('aws elbv2 create-target-group --name "!TG_NAME!" --protocol HTTP --port "!APP_PORT!" --vpc-id "!VPC_ID!" --target-type ip --health-check-path "/actuator/health" --health-check-interval-seconds 30 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region "!AWS_REGION!" --query "TargetGroups[0].TargetGroupArn" --output text') do set "TARGET_GROUP_ARN=%%T"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create Target Group.
        exit /b 1
    )
    echo Target Group ARN: !TARGET_GROUP_ARN!

    aws elbv2 create-listener --load-balancer-arn "!LB_ARN!" --protocol HTTP --port 80 --default-actions "Type=forward,TargetGroupArn=!TARGET_GROUP_ARN!" --region "!AWS_REGION!" >nul
    echo ALB listener created.
)

:: ---------------------------------------------------------------------------
:: Substitute placeholders in task definition (via PowerShell)
:: ---------------------------------------------------------------------------
echo.
echo Preparing task definition...
set "TMP_TASK_DEF=%TEMP%\resortsLite-task-def.json"
copy /Y "!TASK_DEF_FILE!" "!TMP_TASK_DEF!" >nul

powershell -NoProfile -Command ^
  "(Get-Content '!TMP_TASK_DEF!') -replace '{{ACCOUNT_ID}}','!ACCOUNT_ID!' -replace '{{AWS_REGION}}','!AWS_REGION!' -replace '{{IMAGE_URI}}','!IMAGE_URI!' | Set-Content '!TMP_TASK_DEF!'"

:: ---------------------------------------------------------------------------
:: Register task definition
:: ---------------------------------------------------------------------------
echo Registering task definition...
for /f "delims=" %%A in ('aws ecs register-task-definition --cli-input-json "file://!TMP_TASK_DEF!" --region "!AWS_REGION!" --query "taskDefinition.taskDefinitionArn" --output text') do set "TASK_DEF_ARN=%%A"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to register task definition.
    del /f "!TMP_TASK_DEF!" >nul 2>&1
    exit /b 1
)
echo Task Definition ARN: !TASK_DEF_ARN!
del /f "!TMP_TASK_DEF!" >nul 2>&1

:: ---------------------------------------------------------------------------
:: Prepare service definition (via PowerShell)
:: ---------------------------------------------------------------------------
set "TMP_SVC_DEF=%TEMP%\resortsLite-svc-def.json"
copy /Y "!SVC_DEF_FILE!" "!TMP_SVC_DEF!" >nul

powershell -NoProfile -Command ^
  "(Get-Content '!TMP_SVC_DEF!') -replace '{{CLUSTER_NAME}}','!CLUSTER_NAME!' -replace '{{SUBNET_1}}','!SUBNET_1!' -replace '{{SUBNET_2}}','!SUBNET_2!' -replace '{{SECURITY_GROUP}}','!SECURITY_GROUP!' | Set-Content '!TMP_SVC_DEF!'"

if /i "!NEED_LB!"=="y" (
    powershell -NoProfile -Command ^
      "$s=Get-Content '!TMP_SVC_DEF!' | ConvertFrom-Json; $s | Add-Member -Force -NotePropertyName 'loadBalancers' -NotePropertyValue @(@{targetGroupArn='!TARGET_GROUP_ARN!';containerName='!CONTAINER_NAME!';containerPort=!APP_PORT!}); $s | Add-Member -Force -NotePropertyName 'healthCheckGracePeriodSeconds' -NotePropertyValue 300; $s | ConvertTo-Json -Depth 10 | Set-Content '!TMP_SVC_DEF!'"
)

:: ---------------------------------------------------------------------------
:: Create or update ECS service
:: ---------------------------------------------------------------------------
echo.
echo Checking if ECS service '!SERVICE_NAME!' exists...
for /f "delims=" %%E in ('aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[?status!='INACTIVE'].serviceName" --output text 2^>nul') do set "EXISTING_SERVICE=%%E"

if "!EXISTING_SERVICE!"=="" (
    echo Creating ECS service '!SERVICE_NAME!'...
    aws ecs create-service --cli-input-json "file://!TMP_SVC_DEF!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS service.
        del /f "!TMP_SVC_DEF!" >nul 2>&1
        exit /b 1
    )
    echo Service created.
) else (
    echo Updating existing ECS service '!SERVICE_NAME!'...
    aws ecs update-service --cluster "!CLUSTER_NAME!" --service "!SERVICE_NAME!" --task-definition "!TASK_DEF_ARN!" --region "!AWS_REGION!" >nul
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to update ECS service.
        del /f "!TMP_SVC_DEF!" >nul 2>&1
        exit /b 1
    )
    echo Service updated.
)
del /f "!TMP_SVC_DEF!" >nul 2>&1

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
echo Deployment verification:
aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[0].{Status:status,Running:runningCount,Desired:desiredCount,Pending:pendingCount}"

echo.
echo ==============================================
echo   Deployment complete!
echo   Service  : !SERVICE_NAME!
echo   Cluster  : !CLUSTER_NAME!
echo   Region   : !AWS_REGION!
echo   Log Group: !LOG_GROUP!
if not "!LB_DNS!"=="" echo   ALB URL  : http://!LB_DNS!
echo ==============================================
echo.
echo Troubleshooting tips:
echo   View logs : aws logs tail !LOG_GROUP! --follow --region !AWS_REGION!
echo   List tasks: aws ecs list-tasks --cluster !CLUSTER_NAME! --service-name !SERVICE_NAME! --region !AWS_REGION!
echo   Stop svc  : aws ecs update-service --cluster !CLUSTER_NAME! --service !SERVICE_NAME! --desired-count 0 --region !AWS_REGION!

endlocal
