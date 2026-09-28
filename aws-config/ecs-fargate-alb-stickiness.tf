# Terraform Configuration for ResortsLite ECS Fargate Deployment
# Remediation for cz-java-0069: In-Memory Session Storage
# This configuration includes ALB target group stickiness as a transitional strategy

terraform {
  required_version = ">= 1.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

# Variables
variable "vpc_id" {
  description = "VPC ID for ECS deployment"
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs for ECS tasks"
  type        = list(string)
}

variable "public_subnet_ids" {
  description = "Public subnet IDs for ALB"
  type        = list(string)
}

variable "alb_listener_arn" {
  description = "ARN of the ALB HTTPS listener"
  type        = string
}

variable "environment" {
  description = "Environment name (dev, staging, prod)"
  type        = string
  default     = "prod"
}

variable "container_image" {
  description = "Docker image for ResortsLite application"
  type        = string
}

variable "jwt_secret_arn" {
  description = "ARN of JWT secret in AWS Secrets Manager"
  type        = string
}

variable "db_secret_arn" {
  description = "ARN of database credentials in AWS Secrets Manager"
  type        = string
}

# Security Group for ALB
resource "aws_security_group" "alb" {
  name_prefix = "resorts-lite-alb-"
  description = "Security group for ResortsLite ALB"
  vpc_id      = var.vpc_id

  ingress {
    description = "HTTPS from Internet"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTP from Internet (redirect to HTTPS)"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "All outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name        = "resorts-lite-alb-sg"
    Application = "ResortsLite"
    Environment = var.environment
  }
}

# Security Group for ECS Tasks
resource "aws_security_group" "ecs_tasks" {
  name_prefix = "resorts-lite-ecs-"
  description = "Security group for ResortsLite ECS tasks"
  vpc_id      = var.vpc_id

  ingress {
    description     = "HTTP from ALB"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    description = "All outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name        = "resorts-lite-ecs-sg"
    Application = "ResortsLite"
    Environment = var.environment
  }
}

# ALB Target Group with Stickiness (cz-java-0069 remediation)
resource "aws_lb_target_group" "resorts_lite" {
  name        = "resorts-lite-tg-${var.environment}"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip" # Required for ECS Fargate

  health_check {
    enabled             = true
    healthy_threshold   = 2
    unhealthy_threshold = 2
    timeout             = 5
    interval            = 30
    path                = "/actuator/health"
    protocol            = "HTTP"
    matcher             = "200"
  }

  # Session Stickiness Configuration (cz-java-0069 remediation)
  # This is a transitional strategy to minimize session disruption
  # while the stateless JWT architecture is validated in production
  stickiness {
    enabled         = true
    type            = "lb_cookie"
    cookie_duration = 3600 # 1 hour to match JWT token expiration
  }

  deregistration_delay = 30

  tags = {
    Name        = "resorts-lite-target-group"
    Application = "ResortsLite"
    Environment = var.environment
    Remediation = "cz-java-0069"
  }
}

# ALB Listener Rule
resource "aws_lb_listener_rule" "resorts_lite" {
  listener_arn = var.alb_listener_arn
  priority     = 100

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.resorts_lite.arn
  }

  condition {
    path_pattern {
      values = ["/api/*", "/actuator/health"]
    }
  }

  tags = {
    Name        = "resorts-lite-listener-rule"
    Application = "ResortsLite"
    Environment = var.environment
  }
}

# ECS Cluster
resource "aws_ecs_cluster" "main" {
  name = "resorts-lite-cluster-${var.environment}"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = {
    Name        = "resorts-lite-cluster"
    Application = "ResortsLite"
    Environment = var.environment
  }
}

# CloudWatch Log Group
resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/resorts-lite-${var.environment}"
  retention_in_days = 30

  tags = {
    Name        = "resorts-lite-logs"
    Application = "ResortsLite"
    Environment = var.environment
  }
}

# ECS Task Execution Role
resource "aws_iam_role" "ecs_task_execution" {
  name = "resorts-lite-ecs-execution-${var.environment}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })

  tags = {
    Name        = "resorts-lite-ecs-execution-role"
    Application = "ResortsLite"
    Environment = var.environment
  }
}

resource "aws_iam_role_policy_attachment" "ecs_task_execution" {
  role       = aws_iam_role.ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Additional policy for Secrets Manager access
resource "aws_iam_role_policy" "secrets_access" {
  name = "secrets-access"
  role = aws_iam_role.ecs_task_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue"
        ]
        Resource = [
          var.jwt_secret_arn,
          var.db_secret_arn
        ]
      }
    ]
  })
}

# ECS Task Role (for application runtime permissions)
resource "aws_iam_role" "ecs_task" {
  name = "resorts-lite-ecs-task-${var.environment}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })

  tags = {
    Name        = "resorts-lite-ecs-task-role"
    Application = "ResortsLite"
    Environment = var.environment
  }
}

# ECS Task Definition
resource "aws_ecs_task_definition" "app" {
  family                   = "resorts-lite-${var.environment}"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "512"  # 0.5 vCPU
  memory                   = "1024" # 1 GB
  execution_role_arn       = aws_iam_role.ecs_task_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  container_definitions = jsonencode([
    {
      name      = "resorts-lite"
      image     = var.container_image
      essential = true

      portMappings = [
        {
          containerPort = 8080
          protocol      = "tcp"
        }
      ]

      environment = [
        {
          name  = "SPRING_PROFILES_ACTIVE"
          value = var.environment
        },
        {
          name  = "SERVER_PORT"
          value = "8080"
        },
        {
          name  = "JAVA_OPTS"
          value = "-Xmx768m -Xms512m"
        }
      ]

      secrets = [
        {
          name      = "JWT_SECRET"
          valueFrom = "${var.jwt_secret_arn}:jwt_secret::"
        },
        {
          name      = "SPRING_DATASOURCE_URL"
          valueFrom = "${var.db_secret_arn}:url::"
        },
        {
          name      = "SPRING_DATASOURCE_USERNAME"
          valueFrom = "${var.db_secret_arn}:username::"
        },
        {
          name      = "SPRING_DATASOURCE_PASSWORD"
          valueFrom = "${var.db_secret_arn}:password::"
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.app.name
          "awslogs-region"        = data.aws_region.current.name
          "awslogs-stream-prefix" = "ecs"
        }
      }

      healthCheck = {
        command     = ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 3
        startPeriod = 60
      }
    }
  ])

  tags = {
    Name        = "resorts-lite-task-definition"
    Application = "ResortsLite"
    Environment = var.environment
  }
}

# ECS Service with ALB Integration
resource "aws_ecs_service" "app" {
  name            = "resorts-lite-service-${var.environment}"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = 2
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.resorts_lite.arn
    container_name   = "resorts-lite"
    container_port   = 8080
  }

  deployment_configuration {
    maximum_percent         = 200
    minimum_healthy_percent = 100
  }

  # Enable ECS Service Discovery (optional)
  # service_registries {
  #   registry_arn = aws_service_discovery_service.app.arn
  # }

  depends_on = [
    aws_lb_target_group.resorts_lite,
    aws_iam_role_policy_attachment.ecs_task_execution
  ]

  tags = {
    Name        = "resorts-lite-service"
    Application = "ResortsLite"
    Environment = var.environment
  }
}

# Auto Scaling Target
resource "aws_appautoscaling_target" "ecs" {
  max_capacity       = 10
  min_capacity       = 2
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.app.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

# Auto Scaling Policy - CPU
resource "aws_appautoscaling_policy" "ecs_cpu" {
  name               = "resorts-lite-cpu-autoscaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs.resource_id
  scalable_dimension = aws_appautoscaling_target.ecs.scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs.service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
    target_value       = 70.0
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}

# Auto Scaling Policy - Memory
resource "aws_appautoscaling_policy" "ecs_memory" {
  name               = "resorts-lite-memory-autoscaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs.resource_id
  scalable_dimension = aws_appautoscaling_target.ecs.scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs.service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageMemoryUtilization"
    }
    target_value       = 80.0
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}

# Data source for current region
data "aws_region" "current" {}

# Outputs
output "ecs_cluster_name" {
  description = "Name of the ECS cluster"
  value       = aws_ecs_cluster.main.name
}

output "ecs_service_name" {
  description = "Name of the ECS service"
  value       = aws_ecs_service.app.name
}

output "target_group_arn" {
  description = "ARN of the ALB target group"
  value       = aws_lb_target_group.resorts_lite.arn
}

output "security_group_id" {
  description = "Security group ID for ECS tasks"
  value       = aws_security_group.ecs_tasks.id
}

output "log_group_name" {
  description = "CloudWatch log group name"
  value       = aws_cloudwatch_log_group.app.name
}
