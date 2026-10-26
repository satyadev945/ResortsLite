# =============================================================================
# Multi-Stage Dockerfile for ResortsLite Spring Boot Application
# Java 8 / Spring Boot 2.7.x / Maven
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: Builder
# -----------------------------------------------------------------------------
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

# -----------------------------------------------------------------------------
# Stage 2: Runtime
# -----------------------------------------------------------------------------
FROM eclipse-temurin:8-jre

# Metadata labels
LABEL maintainer="ResortsLite Team" \
      application="resortslite" \
      version="1.0.0" \
      description="Legacy resort booking demo — Java 8 / Spring Boot 2.7.x"

# Set timezone
ENV TZ=UTC

# JVM tuning for containerised environments
ENV JAVA_OPTS="-Xms256m -Xmx512m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UnlockExperimentalVMOptions \
  -Djava.security.egd=file:/dev/./urandom"

# Spring profile
ENV SPRING_PROFILES_ACTIVE=docker

# Application port
EXPOSE 8080

# Create a non-root user for security
RUN groupadd --system appgroup && \
    useradd --system --gid appgroup --home /app --shell /bin/false appuser

WORKDIR /app

# Copy the fat JAR from the builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Set ownership
RUN chown -R appuser:appgroup /app

USER appuser

# Start the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
