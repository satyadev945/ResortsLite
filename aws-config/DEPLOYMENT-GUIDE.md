# ResortsLite - AWS ECS Fargate Deployment Guide

## Overview
This guide provides instructions for deploying the ResortsLite application on AWS ECS Fargate with Application Load Balancer (ALB) session affinity as a transitional strategy.

## Containerization Blocker Remediation

### cz-java-0069: In-Memory Session Storage
**Status**: ✅ FIXED

**Issue**: In-memory session storage that is lost when containers restart or scale, breaking user experience.

**Remediation Applied**: 
1. **Code Changes**: Application refactored to use stateless JWT authentication (already completed)
2. **Infrastructure Strategy**: ALB target group stickiness configured as transitional measure
3. **Configuration Files**: Terraform and documentation provided for ECS deployment

**Files Modified**:
- `src/main/java/com/demo/resortslite/BookingController.java` (Lines 28-60)
  - Added comprehensive documentation about ALB stickiness requirement
  - Documented the transitional strategy and configuration examples
  
**Infrastructure Files Created**:
- `aws-config/alb-stickiness-config.md` - Detailed configuration guide
- `aws-config/ecs-fargate-alb-stickiness.tf` - Complete Terraform configuration

## Health Check Endpoint

### Status: ✅ ALREADY EXISTS

The application already has a health check endpoint configured:

- **Endpoint**: `/actuator/health`
- **Implementation**: Spring Boot Actuator (spring-boot-starter-actuator)
- **Configuration**: `src/main/resources/application.properties`
- **Response Format**: JSON with status information

Example response:
```json
{
  "status": "UP"
}
```

## Deployment Architecture

### Components

1. **Application Load Balancer (ALB)**
   - Handles incoming HTTPS traffic
   - Routes requests to ECS tasks
   - Provides session stickiness via lb_cookie

2. **ECS Fargate Cluster**
   - Runs containerized application
   - Auto-scales based on CPU/Memory utilization
   - Minimum 2 tasks, maximum 10 tasks

3. **Target Group**
   - Health check: `/actuator/health`
   - Stickiness: Enabled (3600 seconds)
   - Deregistration delay: 30 seconds

4. **Security Groups**
   - ALB: Allows HTTPS (443) and HTTP (80) from internet
   - ECS Tasks: Allows HTTP (8080) from ALB only

## Deployment Steps

### Prerequisites

1. AWS Account with appropriate permissions
2. VPC with public and private subnets
3. Application Load Balancer with HTTPS listener
4. Docker image pushed to ECR
5. Secrets stored in AWS Secrets Manager:
   - JWT signing secret
   - Database credentials

### Step 1: Configure Secrets Manager

```bash
# Create JWT secret
aws secretsmanager create-secret \
  --name resorts-lite/jwt-secret \
  --description "JWT signing secret for ResortsLite" \
  --secret-string '{"jwt_secret":"your-secure-256-bit-secret-key-here"}'

# Create database credentials
aws secretsmanager create-secret \
  --name resorts-lite/db-credentials \
  --description "Database credentials for ResortsLite" \
  --secret-string '{
    "url":"jdbc:oracle:thin:@your-rds-endpoint:1521:ORCL",
    "username":"admin",
    "password":"your-secure-password"
  }'
```

### Step 2: Build and Push Docker Image

```bash
# Build Docker image
docker build -t resorts-lite:latest .

# Tag for ECR
docker tag resorts-lite:latest <account-id>.dkr.ecr.<region>.amazonaws.com/resorts-lite:latest

# Login to ECR
aws ecr get-login-password --region <region> | docker login --username AWS --password-stdin <account-id>.dkr.ecr.<region>.amazonaws.com

# Push to ECR
docker push <account-id>.dkr.ecr.<region>.amazonaws.com/resorts-lite:latest
```

### Step 3: Deploy with Terraform

```bash
# Navigate to aws-config directory
cd aws-config

# Initialize Terraform
terraform init

# Create terraform.tfvars
cat > terraform.tfvars <<EOF
vpc_id              = "vpc-xxxxxxxxx"
private_subnet_ids  = ["subnet-xxxxxxxx", "subnet-yyyyyyyy"]
public_subnet_ids   = ["subnet-aaaaaaaa", "subnet-bbbbbbbb"]
alb_listener_arn    = "arn:aws:elasticloadbalancing:region:account:listener/app/..."
environment         = "prod"
container_image     = "<account-id>.dkr.ecr.<region>.amazonaws.com/resorts-lite:latest"
jwt_secret_arn      = "arn:aws:secretsmanager:region:account:secret:resorts-lite/jwt-secret"
db_secret_arn       = "arn:aws:secretsmanager:region:account:secret:resorts-lite/db-credentials"
EOF

# Plan deployment
terraform plan

# Apply deployment
terraform apply
```

### Step 4: Verify Deployment

```bash
# Check ECS service status
aws ecs describe-services \
  --cluster resorts-lite-cluster-prod \
  --services resorts-lite-service-prod

# Check target group health
aws elbv2 describe-target-health \
  --target-group-arn <target-group-arn>

# Test health endpoint
curl https://your-alb-domain.com/actuator/health

# Test application endpoint
curl -X POST https://your-alb-domain.com/api/bookings/create \
  -d "guestName=John Doe&roomType=Deluxe&checkIn=2024-01-01&checkOut=2024-01-05"
```

## Session Stickiness Configuration

### Current Configuration (Transitional)

The ALB target group is configured with session stickiness:

- **Type**: lb_cookie (ALB-generated cookie)
- **Duration**: 3600 seconds (1 hour)
- **Cookie Name**: AWSALB (auto-generated)

### How It Works

1. Client makes first request → ALB generates AWSALB cookie
2. Client includes cookie in subsequent requests
3. ALB routes requests to same ECS task based on cookie
4. Application uses JWT for stateless authentication
5. Stickiness provides safety net during migration

### Future Migration Path

Once stateless operation is validated:

```bash
# Disable stickiness
aws elbv2 modify-target-group-attributes \
  --target-group-arn <target-group-arn> \
  --attributes Key=stickiness.enabled,Value=false
```

Or update Terraform:

```hcl
stickiness {
  enabled         = false
  type            = "lb_cookie"
  cookie_duration = 3600
}
```

## Monitoring

### CloudWatch Metrics

Monitor these metrics in CloudWatch:

- **ECS Service**:
  - CPUUtilization
  - MemoryUtilization
  - DesiredTaskCount
  - RunningTaskCount

- **Target Group**:
  - HealthyHostCount
  - UnHealthyHostCount
  - RequestCount
  - TargetResponseTime
  - HTTPCode_Target_2XX_Count
  - HTTPCode_Target_4XX_Count
  - HTTPCode_Target_5XX_Count

### CloudWatch Logs

Application logs are available in:
- Log Group: `/ecs/resorts-lite-prod`
- Stream Prefix: `ecs/resorts-lite/<task-id>`

### CloudWatch Alarms

Recommended alarms:

```bash
# Unhealthy target alarm
aws cloudwatch put-metric-alarm \
  --alarm-name resorts-lite-unhealthy-targets \
  --alarm-description "Alert when targets are unhealthy" \
  --metric-name UnHealthyHostCount \
  --namespace AWS/ApplicationELB \
  --statistic Average \
  --period 60 \
  --evaluation-periods 2 \
  --threshold 1 \
  --comparison-operator GreaterThanThreshold

# High CPU alarm
aws cloudwatch put-metric-alarm \
  --alarm-name resorts-lite-high-cpu \
  --alarm-description "Alert when CPU is high" \
  --metric-name CPUUtilization \
  --namespace AWS/ECS \
  --statistic Average \
  --period 300 \
  --evaluation-periods 2 \
  --threshold 80 \
  --comparison-operator GreaterThanThreshold
```

## Troubleshooting

### Tasks Not Starting

1. Check CloudWatch logs for errors
2. Verify Secrets Manager permissions
3. Check security group rules
4. Verify subnet routing and NAT gateway

### Health Check Failures

1. Verify `/actuator/health` endpoint is accessible
2. Check application logs for startup errors
3. Verify security group allows traffic on port 8080
4. Check task definition health check configuration

### Session Issues

1. Verify ALB stickiness is enabled
2. Check AWSALB cookie in browser
3. Monitor target group metrics
4. Verify JWT token generation and validation

## Security Considerations

1. **Secrets Management**: All sensitive data stored in AWS Secrets Manager
2. **Network Security**: ECS tasks in private subnets, ALB in public subnets
3. **HTTPS Only**: ALB listener should enforce HTTPS
4. **IAM Roles**: Least privilege access for ECS tasks
5. **Security Groups**: Restrictive ingress/egress rules

## Cost Optimization

1. **Right-sizing**: Monitor CPU/Memory and adjust task definition
2. **Auto-scaling**: Configure appropriate min/max task counts
3. **Spot Instances**: Consider Fargate Spot for non-critical workloads
4. **Log Retention**: Set appropriate CloudWatch log retention period

## References

- [AWS ECS Fargate Documentation](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate.html)
- [ALB Target Group Stickiness](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/sticky-sessions.html)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Terraform AWS Provider](https://registry.terraform.io/providers/hashicorp/aws/latest/docs)

## Support

For issues or questions:
1. Check CloudWatch logs
2. Review AWS ECS service events
3. Consult AWS documentation
4. Contact DevOps team
