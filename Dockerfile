# ============================================================
# Stage 1: Builder
# ============================================================
FROM maven:3.8.6-openjdk-8-slim AS builder

WORKDIR /workspace

# Copy dependency descriptors first for layer caching
COPY pom.xml .

# Download all dependencies (cached layer unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy full source tree (wrapper files are excluded via .dockerignore)
COPY src ./src

# Build the application JAR (skip tests for Docker build)
RUN mvn clean package -DskipTests -B

# ============================================================
# Stage 2: Runtime
# ============================================================
FROM eclipse-temurin:8-jdk

# Timezone configuration
ENV TZ=UTC

WORKDIR /app

# Create non-root user for security
RUN groupadd --system appgroup && \
    useradd --system --gid appgroup --no-create-home appuser

# Copy the built JAR from builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Set ownership
RUN chown -R appuser:appgroup /app

USER appuser

# Application port (extracted from application.properties: server.port=8080)
EXPOSE 8080

# JVM optimisations for containerised environments
ENV JAVA_OPTS="-Xms256m -Xmx512m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring profile and application environment variables
ENV SPRING_PROFILES_ACTIVE=docker
ENV SERVER_PORT=8080

# Redis configuration (distributed cache)
ENV REDIS_HOST=localhost
ENV REDIS_PORT=6379
ENV BOOKING_CACHE_TTL_SECONDS=3600

# Azure Service Bus configuration
ENV AZURE_SERVICE_BUS_CONNECTION_STRING=
ENV AZURE_SERVICE_BUS_BOOKING_QUEUE=booking-events-queue
ENV AZURE_SERVICE_BUS_REPORT_QUEUE=report-events-queue

# JWT secret key (override via Azure Key Vault CSI Driver in AKS)
ENV JWT_SECRET_KEY=default-dev-secret-key-replace-in-production

# Report and backup paths (mount via Azure Key Vault CSI Driver or PVC)
ENV REPORT_BASE_PATH=/mnt/reports
ENV BACKUP_PATH=/mnt/backups

# Database host (override for non-H2 deployments)
ENV DB_HOST=localhost

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
