# ============================================================
# Stage 1: Builder
# ============================================================
FROM maven:3.8.6-openjdk-8-slim AS builder

WORKDIR /workspace

# Copy Maven build descriptor first for dependency layer caching
COPY pom.xml .

# Download all dependencies (cached layer unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy the full source tree
COPY src ./src

# Build the application JAR (skip tests for Docker build)
RUN mvn clean package -DskipTests -B

# ============================================================
# Stage 2: Runtime
# ============================================================
FROM amazoncorretto:8

# Timezone configuration
ENV TZ=UTC

# Create a non-root user for security
RUN groupadd -r appgroup && useradd -r -g appgroup -d /app -s /sbin/nologin appuser

WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Set ownership
RUN chown -R appuser:appgroup /app

USER appuser

# Application port (from application.properties: server.port=8080)
EXPOSE 8080

# JVM tuning: container-aware memory settings
ENV JAVA_OPTS="-Xms256m -Xmx512m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UnlockExperimentalVMOptions \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring profile for containerised deployments
ENV SPRING_PROFILES_ACTIVE=docker

# Redis connection (Amazon ElastiCache) — override at runtime
ENV REDIS_HOST=localhost
ENV REDIS_PORT=6379

# Report base path — override via EKS ConfigMap
ENV REPORT_BASE_PATH=/var/reports

# Payment API URL — override via EKS ConfigMap
ENV PAYMENT_API_URL=http://payment-service:9090/payments/charge

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
