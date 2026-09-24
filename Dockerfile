# ============================================================
# Multi-stage Dockerfile for ResortsLite (Spring Boot 2.7.x / Java 8)
# Builder : maven:3.8.6-openjdk-8-slim
# Runtime : eclipse-temurin:8-jre  (explicit base image)
# ============================================================

# ---- Stage 1: Build ----
FROM maven:3.8.6-openjdk-8-slim AS builder

WORKDIR /workspace

# Copy dependency manifests first for layer caching
COPY pom.xml .

# Download all dependencies (cached unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy the full source tree
COPY src ./src

# Build the fat JAR, skip tests
RUN mvn clean package -DskipTests -B

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:8-jre

# Timezone and locale
ENV TZ=UTC \
    LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8

# JVM tuning for containerised environments
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UnlockExperimentalVMOptions \
               -Xms256m \
               -Xmx512m \
               -Djava.security.egd=file:/dev/./urandom"

# Spring Boot profile
ENV SPRING_PROFILES_ACTIVE=docker

# Redis / Memorystore connection (overridden at runtime via GKE env / Secret Manager)
ENV REDIS_HOST=localhost \
    REDIS_PORT=6379 \
    REDIS_PASSWORD=

# External service endpoints (overridden via GKE ConfigMap)
ENV PAYMENT_API_URL=http://payment-svc.payments.svc.cluster.local:9090/payments/charge \
    REPORT_BASE_PATH=/var/legacy/reports/

# Application port
EXPOSE 8080

# Create non-root user for security
RUN groupadd --system appgroup && \
    useradd --system --gid appgroup --no-create-home appuser

WORKDIR /app

# Copy the fat JAR from the builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Set ownership
RUN chown -R appuser:appgroup /app

USER appuser

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
