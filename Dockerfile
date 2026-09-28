# Multi-stage Dockerfile for ResortsLite Spring Boot Application
# Java 8 / Spring Boot 2.7.x / Maven

# ============================================
# Stage 1: Build Stage
# ============================================
FROM maven:3.8.6-openjdk-8-slim AS builder

WORKDIR /workspace

# Copy Maven POM file first for dependency caching
COPY pom.xml .

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application (skip tests for faster builds)
RUN mvn clean package -DskipTests

# ============================================
# Stage 2: Runtime Stage
# ============================================
FROM amazoncorretto:8

# Set working directory
WORKDIR /app

# Create non-root user for security
RUN groupadd -r appuser && useradd -r -g appuser appuser

# Copy JAR from builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Set ownership to non-root user
RUN chown -R appuser:appuser /app

# Switch to non-root user
USER appuser

# Set JVM options for containerized environment
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Set timezone
ENV TZ=UTC

# Expose application port
EXPOSE 8080

# Expose management/actuator port (if different)
EXPOSE 8081

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
