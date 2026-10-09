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

WORKDIR /app

# Create non-root user for security
RUN groupadd -r appgroup && useradd -r -g appgroup -s /sbin/nologin appuser

# Set timezone
ENV TZ=UTC

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

# Spring Boot profile and configuration
ENV SPRING_PROFILES_ACTIVE=docker

# Redis connection (override via Kubernetes ConfigMap/Secret)
ENV REDIS_HOST=localhost
ENV REDIS_PORT=6379
ENV REDIS_PASSWORD=
ENV REDIS_SSL=false

# Application-specific environment variables
ENV PAYMENT_API_URL=http://payment-service:9090/payments/charge
ENV BOOKING_CACHE_TTL_SECONDS=3600
ENV REPORT_BASE_PATH=/var/reports

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
