@echo off
setlocal enabledelayedexpansion

REM =============================================================================
REM deploy-image.bat — Deploy ResortsLite to AWS ECS Fargate (Windows)
REM Usage: scripts\deploy-image.bat
REM Prerequisites: AWS CLI configured, Python 3 in PATH
REM =============================================================================

set "SERVICE_NAME=resortslite-service"
set "TASK_FAMILY=resortslite-task"
set "LOG_GROUP=/ecs/resortslite"
set "TASK_DEF_FILE=ecs\task-definition.json"
set "SERVICE_DEF_FILE=ecs\service-definition.json"

echo ==============================================
echo   ResortsLite - ECS Fargate Deployment
echo ==============================================

REM Collect deployment parameters
set /p "AWS_REGION=Enter AWS region [us-east-1]: "
if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

set /p "CLUSTER_NAME=Enter ECS cluster name [resortslite-cluster]: "
if "!CLUSTER_NAME!"=="" set "CLUSTER_NAME=resortslite-cluster"

set /p "IMAGE_URI=Enter ECR image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Image URI is required.
    exit /b 1
)

set /p "VPC_ID=Enter VPC ID: "
if "!VPC_ID!"=="" (
    echo ERROR: VPC ID is required.
    exit /b 1
)

set /p "SUBNETS_INPUT=Enter subnet IDs comma-separated e.g. subnet-aaa,subnet-bbb: "
if "!SUBNETS_INPUT!"=="" (
    echo ERROR: At least one subnet ID is required.
    exit /b 1
)

REM Split subnets
for /f "tokens=1,2 delims=," %%a in ("!SUBNETS_INPUT!") do (
    set "SUBNET_1=%%a"
    set "SUBNET_2=%%b"
)
if "!SUBNET_2!"=="" set "SUBNET_2=!SUBNET_1!"

set /p "SECURITY_GROUP=Enter security group ID: "
if "!SECURITY_GROUP!"=="" (
    echo ERROR: Security group ID is required.
    exit /b 1
)

REM Retrieve AWS Account ID
echo.
echo Retrieving AWS Account ID...
for /f "delims=" %%i in ('aws sts get-caller-identity --query Account --output text') do set "ACCOUNT_ID=%%i"
if !ERRORLEVEL! neq 0 (
    echo Failed to retrieve AWS Account ID. Check AWS CLI configuration.
    exit /b 1
)
echo Account ID: !ACCOUNT_ID!

REM Ensure CloudWatch log group exists
echo.
echo Ensuring CloudWatch log group exists: !LOG_GROUP!
aws logs create-log-group --log-group-name "!LOG_GROUP!" --region "!AWS_REGION!" >nul 2>&1

REM Ensure ECS cluster exists
echo.
echo Checking ECS cluster: !CLUSTER_NAME!
for /f "delims=" %%i in ('aws ecs describe-clusters --clusters "!CLUSTER_NAME!" --region "!AWS_REGION!" --query "clusters[0].status" --output text 2^>nul') do set "CLUSTER_STATUS=%%i"
if not "!CLUSTER_STATUS!"=="ACTIVE" (
    echo Creating ECS cluster: !CLUSTER_NAME!
    aws ecs create-cluster --cluster-name "!CLUSTER_NAME!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo Failed to create ECS cluster.
        exit /b 1
    )
)

REM Load balancer prompt
echo.
set /p "NEED_ALB=Do you need an Application Load Balancer for this service? (y/n) [y]: "
if "!NEED_ALB!"=="" set "NEED_ALB=y"

set "TARGET_GROUP_ARN="
set "ALB_DNS="

if /i "!NEED_ALB!"=="y" (
    echo.
    echo Creating Application Load Balancer...

    for /f "delims=" %%i in ('aws elbv2 create-load-balancer --name "resortslite-alb" --subnets "!SUBNET_1!" "!SUBNET_2!" --security-groups "!SECURITY_GROUP!" --scheme internet-facing --type application --region "!AWS_REGION!" --query "LoadBalancers[0].LoadBalancerArn" --output text') do set "ALB_ARN=%%i"
    if !ERRORLEVEL! neq 0 (
        echo Failed to create ALB.
        exit /b 1
    )
    echo ALB ARN: !ALB_ARN!

    for /f "delims=" %%i in ('aws elbv2 describe-load-balancers --load-balancer-arns "!ALB_ARN!" --region "!AWS_REGION!" --query "LoadBalancers[0].DNSName" --output text') do set "ALB_DNS=%%i"

    for /f "delims=" %%i in ('aws elbv2 create-target-group --name "resortslite-tg" --protocol HTTP --port 8080 --vpc-id "!VPC_ID!" --target-type ip --health-check-path "/actuator/health" --health-check-interval-seconds 30 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region "!AWS_REGION!" --query "TargetGroups[0].TargetGroupArn" --output text') do set "TARGET_GROUP_ARN=%%i"
    if !ERRORLEVEL! neq 0 (
        echo Failed to create Target Group.
        exit /b 1
    )
    echo Target Group ARN: !TARGET_GROUP_ARN!

    aws elbv2 create-listener --load-balancer-arn "!ALB_ARN!" --protocol HTTP --port 80 --default-actions "Type=forward,TargetGroupArn=!TARGET_GROUP_ARN!" --region "!AWS_REGION!" >nul
    echo ALB Listener created on port 80.
)

REM Prepare task definition (copy and substitute placeholders)
echo.
echo Preparing task definition...
copy /Y "!TASK_DEF_FILE!" "%TEMP%\task-definition-deploy.json" >nul

powershell -NoProfile -Command ^
  "(Get-Content '%TEMP%\task-definition-deploy.json') -replace '{{IMAGE_URI}}','!IMAGE_URI!' -replace '{{AWS_REGION}}','!AWS_REGION!' -replace '{{ACCOUNT_ID}}','!ACCOUNT_ID!' | Set-Content '%TEMP%\task-definition-deploy.json'"

REM Register task definition
echo Registering ECS task definition...
for /f "delims=" %%i in ('aws ecs register-task-definition --cli-input-json "file://%TEMP%\task-definition-deploy.json" --region "!AWS_REGION!" --query "taskDefinition.taskDefinitionArn" --output text') do set "TASK_DEF_ARN=%%i"
if !ERRORLEVEL! neq 0 (
    echo Failed to register task definition.
    exit /b 1
)
echo Task Definition ARN: !TASK_DEF_ARN!

REM Prepare service definition
echo.
echo Preparing service definition...
copy /Y "!SERVICE_DEF_FILE!" "%TEMP%\service-definition-deploy.json" >nul

powershell -NoProfile -Command ^
  "(Get-Content '%TEMP%\service-definition-deploy.json') -replace '{{CLUSTER_NAME}}','!CLUSTER_NAME!' -replace '{{SUBNET_1}}','!SUBNET_1!' -replace '{{SUBNET_2}}','!SUBNET_2!' -replace '{{SECURITY_GROUP}}','!SECURITY_GROUP!' | Set-Content '%TEMP%\service-definition-deploy.json'"

REM Inject task definition ARN and handle load balancer via Python
if "!TARGET_GROUP_ARN!"=="" (
    python -c "import json; f=open(r'%TEMP%\service-definition-deploy.json'); d=json.load(f); f.close(); d['taskDefinition']='!TASK_DEF_ARN!'; d.pop('loadBalancers',None); d.pop('healthCheckGracePeriodSeconds',None); f=open(r'%TEMP%\service-definition-deploy.json','w'); json.dump(d,f,indent=2); f.close()"
) else (
    python -c "import json,sys; f=open(r'%TEMP%\service-definition-deploy.json'); d=json.load(f); f.close(); d['taskDefinition']='!TASK_DEF_ARN!'; d['loadBalancers']=[{'targetGroupArn':'!TARGET_GROUP_ARN!','containerName':'resortslite','containerPort':8080}]; d['healthCheckGracePeriodSeconds']=300; f=open(r'%TEMP%\service-definition-deploy.json','w'); json.dump(d,f,indent=2); f.close()"
)

REM Create or update ECS service
echo.
for /f "delims=" %%i in ('aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[?status==''ACTIVE''].serviceName" --output text 2^>nul') do set "EXISTING_SERVICE=%%i"

if "!EXISTING_SERVICE!"=="" (
    echo Creating ECS service: !SERVICE_NAME!
    aws ecs create-service --cli-input-json "file://%TEMP%\service-definition-deploy.json" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo Failed to create ECS service.
        exit /b 1
    )
) else (
    echo Updating existing ECS service: !SERVICE_NAME!
    aws ecs update-service --cluster "!CLUSTER_NAME!" --service "!SERVICE_NAME!" --task-definition "!TASK_DEF_ARN!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo Failed to update ECS service.
        exit /b 1
    )
)

REM Wait for service stability
echo.
echo Waiting for service to stabilise (this may take a few minutes)...
aws ecs wait services-stable --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!"
if !ERRORLEVEL! neq 0 (
    echo WARNING: Service did not stabilise within the expected time. Check ECS console.
)

REM Deployment summary
echo.
echo ==============================================
echo   Deployment Complete!
echo ==============================================
aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[0].{Status:status,Running:runningCount,Desired:desiredCount}" --output table

echo.
echo CloudWatch Log Group : !LOG_GROUP!
if not "!ALB_DNS!"=="" (
    echo Application URL      : http://!ALB_DNS!
)
echo.
echo Troubleshooting tips:
echo   - View logs  : aws logs tail !LOG_GROUP! --follow --region !AWS_REGION!
echo   - List tasks : aws ecs list-tasks --cluster !CLUSTER_NAME! --region !AWS_REGION!

endlocal
exit /b 0
