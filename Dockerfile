# ============================================================
# Stage 1: Builder
# ============================================================
FROM maven:3.8.6-openjdk-8-slim AS builder

WORKDIR /workspace

# Copy dependency descriptor first for layer caching
COPY pom.xml .

# Download all dependencies (cached layer unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application JAR (skip tests for Docker build)
RUN mvn clean package -DskipTests -B

# ============================================================
# Stage 2: Runtime
# ============================================================
FROM amazoncorretto:8

# Set timezone
ENV TZ=UTC

WORKDIR /app

# Create non-root user for security
RUN groupadd -r appgroup && useradd -r -g appgroup -s /sbin/nologin appuser

# Copy the built JAR from builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Set ownership
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose application port
EXPOSE 8080

# JVM optimizations for containerized environments
ENV JAVA_OPTS="-Xmx512m -Xms256m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring profile and runtime environment variables
ENV SPRING_PROFILES_ACTIVE=docker
ENV SERVER_PORT=8080

# Redis connection (Amazon ElastiCache) — injected at runtime
ENV REDIS_HOST=localhost
ENV REDIS_PORT=6379

# PostgreSQL datasource — injected at runtime via Kubernetes secrets/configmaps
ENV SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/resortslite
ENV SPRING_DATASOURCE_USERNAME=admin
ENV SPRING_DATASOURCE_PASSWORD=changeme

# Internal service endpoints — injected at runtime
ENV PAYMENT_API_URL=http://payment-service:9090/payments/charge
ENV APP_PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge
ENV APP_INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms
ENV APP_NOTIFICATION_ENDPOINT=http://notify.internal:7070/send

# Report base path
ENV REPORT_BASE_PATH=/tmp/reports/

# Entrypoint
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
