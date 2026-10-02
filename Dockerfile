# ============================================================
# Stage 1: Builder
# ============================================================
FROM maven:3.8.6-openjdk-8-slim AS builder

WORKDIR /workspace

# Copy dependency manifests first for layer caching
COPY pom.xml .

# Download all dependencies (cached layer unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy full source tree
COPY src ./src

# Build the application JAR (skip tests for Docker build)
RUN mvn clean package -DskipTests -B

# ============================================================
# Stage 2: Runtime
# ============================================================
FROM eclipse-temurin:8-jdk-alpine

# Timezone configuration
ENV TZ=UTC

# Create non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Set ownership
RUN chown -R appuser:appgroup /app

USER appuser

# Application port
EXPOSE 8080

# JVM optimizations for containerized environments
ENV JAVA_OPTS="-Xms256m -Xmx512m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring Boot profile
ENV SPRING_PROFILES_ACTIVE=docker

# Redis connection (override via Kubernetes ConfigMap / EKS Pod spec)
ENV REDIS_HOST=localhost
ENV REDIS_PORT=6379

# File path configuration (override via Kubernetes ConfigMap / EKS Pod spec)
ENV REPORT_BASE_PATH=/var/reports
ENV BACKUP_PATH=/var/backups/resorts

# Payment API URL (override via Kubernetes ConfigMap / EKS Pod spec)
ENV PAYMENT_API_URL=http://payment-svc.payments.svc.cluster.local:9090/payments/charge

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
