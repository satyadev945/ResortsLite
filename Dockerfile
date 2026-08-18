# =============================================================================
# ResortsLite - Multi-Stage Dockerfile
# Framework : Spring Boot 2.7.18
# Java      : 8
# Build Tool: Maven
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1: Builder
# ---------------------------------------------------------------------------
FROM maven:3.9.4-eclipse-temurin-8 AS builder

WORKDIR /workspace

# Copy dependency descriptor first for layer caching
COPY pom.xml .

# Download all dependencies offline (cached layer unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy the full source tree
COPY src ./src

# Build the fat JAR (skip tests in Docker build)
RUN mvn clean package -DskipTests -B

# ---------------------------------------------------------------------------
# Stage 2: Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:8-jdk

# Metadata
LABEL maintainer="ResortsLite Team" \
      application="resortsLite" \
      version="1.0.0" \
      description="Legacy resort booking demo — Java 8 / Spring Boot 2.7.x"

# Timezone
ENV TZ=UTC

# JVM tuning for containerised environments
ENV JAVA_OPTS="-Xmx512m -Xms256m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring profile
ENV SPRING_PROFILES_ACTIVE=docker

# Application environment variables (override at runtime)
ENV SERVER_PORT=8080
ENV JWT_SECRET=default-dev-secret-change-in-production
ENV MEMCACHED_ENDPOINT=localhost:11211
ENV INVENTORY_SERVICE_URL=http://inventory-service:8081
ENV PAYMENT_SERVICE_URL=http://payment-service:9090/payments/charge
ENV DB_HOST=db-service
ENV APP_REPORT_BASE_PATH=/mnt/efs/reports

# Create a non-root user for security
RUN groupadd --system appgroup && \
    useradd --system --gid appgroup --shell /bin/false appuser

# Application directory
WORKDIR /app

# Create log and report directories
RUN mkdir -p /app/logs /mnt/efs/reports && \
    chown -R appuser:appgroup /app /mnt/efs/reports

# Copy the fat JAR from the builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Ensure the JAR is owned by the non-root user
RUN chown appuser:appgroup app.jar

# Switch to non-root user
USER appuser

# Expose application port
EXPOSE 8080

# Graceful shutdown support (Spring Boot handles SIGTERM)
STOPSIGNAL SIGTERM

# Entry point — exec form for proper signal propagation
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
