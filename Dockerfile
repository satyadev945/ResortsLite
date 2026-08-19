# ============================================================
# Stage 1: Builder
# ============================================================
FROM maven:3.8.6-openjdk-8-slim AS builder

WORKDIR /workspace

# Copy dependency descriptor first for layer caching
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
FROM eclipse-temurin:8-jdk-alpine

# Timezone configuration
ENV TZ=UTC

# Create a non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Change ownership to non-root user
RUN chown -R appuser:appgroup /app

USER appuser

# Application port (from application.properties: server.port=8080)
EXPOSE 8080

# JVM optimisations for containerised environments
ENV JAVA_OPTS="-Xms256m -Xmx512m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring profile and application-specific environment variables
ENV SPRING_PROFILES_ACTIVE=docker
ENV REDIS_HOST=redis-service
ENV REDIS_PORT=6379
ENV GCP_PROJECT_ID=my-gcp-project
ENV PUBSUB_BOOKING_TOPIC=booking-events
ENV PUBSUB_REPORT_TOPIC=report-events
ENV JWT_SIGNING_KEY=default-dev-signing-key-replace-in-production
ENV REPORT_BASE_PATH=/var/reports
ENV BOOKING_CACHE_TTL_SECONDS=3600

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
