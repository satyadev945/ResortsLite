# ============================================================
# Stage 1: Builder
# ============================================================
FROM maven:3.8.6-openjdk-8-slim AS builder

WORKDIR /workspace

# Copy dependency descriptor first for layer caching
COPY pom.xml .

# Download all dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application JAR (skip tests for Docker build)
RUN mvn clean package -DskipTests -B

# ============================================================
# Stage 2: Runtime
# ============================================================
FROM eclipse-temurin:8-jre

# Set working directory
WORKDIR /app

# Create non-root user for security
RUN groupadd -r appgroup && useradd -r -g appgroup -s /bin/false appuser

# Set timezone
ENV TZ=UTC

# JVM optimizations for containerized environments
ENV JAVA_OPTS="-Xmx512m -Xms256m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UnlockExperimentalVMOptions \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring Boot environment
ENV SPRING_PROFILES_ACTIVE=docker

# Application environment variables (overridable at runtime)
ENV SERVER_PORT=8080
ENV REPORT_BASE_PATH=/reports
ENV BACKUP_PATH=/backups

# Copy the built JAR from builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Create required directories and set ownership
RUN mkdir -p /reports /backups && \
    chown -R appuser:appgroup /app /reports /backups

# Switch to non-root user
USER appuser

# Expose application port
EXPOSE 8080

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
