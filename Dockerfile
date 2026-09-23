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
FROM eclipse-temurin:8-jre

# Timezone configuration
ENV TZ=UTC

# Create a non-root user for security
RUN groupadd --system appgroup && useradd --system --gid appgroup appuser

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

# Redis connection (Amazon ElastiCache) — override via Kubernetes Secret / ConfigMap
ENV REDIS_HOST=localhost
ENV REDIS_PORT=6379
ENV REDIS_PASSWORD=

# Report / backup paths — override via Kubernetes ConfigMap
ENV REPORT_BASE_PATH=/var/reports
ENV BACKUP_PATH=/var/backups/nightly

# Booking cache TTL — override via Kubernetes ConfigMap
ENV BOOKING_CACHE_TTL_SECONDS=3600

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
