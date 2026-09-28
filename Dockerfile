# Multi-stage Dockerfile for ResortsLite Spring Boot Application
# Optimized for AWS ECS Fargate deployment with Java 8
# Base image: eclipse-temurin:8-jdk-alpine (explicit requirement)

# ============================================================================
# Stage 1: Builder - Maven build with dependency caching
# ============================================================================
FROM maven:3.9.4-eclipse-temurin-8 AS builder

WORKDIR /workspace

# Copy pom.xml first for better dependency caching
COPY pom.xml .

# Download dependencies (cached layer if pom.xml unchanged)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build application (skip tests for Docker builds)
RUN mvn clean package -DskipTests -B

# ============================================================================
# Stage 2: Runtime - Lightweight JRE image
# ============================================================================
FROM eclipse-temurin:8-jdk-alpine

# Create non-root user for security best practices
RUN addgroup -S appuser && adduser -S appuser -G appuser

# Set working directory
WORKDIR /app

# Copy JAR from builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Create directories for EFS volume mounts (configured in ECS task definition)
RUN mkdir -p /mnt/efs/reports /mnt/efs/backups && \
    chown -R appuser:appuser /mnt/efs /app

# Switch to non-root user
USER appuser

# Expose application port (configurable via SERVER_PORT env var)
EXPOSE 8080

# JVM options optimized for containerized environments
# - UseContainerSupport: JVM respects container memory limits
# - MaxRAMPercentage: Use up to 75% of container memory for heap
# - UseG1GC: Low-latency garbage collector suitable for microservices
ENV JAVA_OPTS="-Xmx512m -Xms256m \
    -XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+UseG1GC \
    -Djava.security.egd=file:/dev/./urandom \
    -Duser.timezone=UTC"

# Application configuration
ENV SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE=docker

# Run Spring Boot application
# Note: Health checks handled by ECS service via /actuator/health endpoint
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
