# ============================================================
# Stage 1: Builder
# ============================================================
FROM maven:3.9.4-eclipse-temurin-8 AS builder

WORKDIR /workspace

# Copy build descriptor first for dependency layer caching
COPY pom.xml .

# Download all dependencies (cached layer unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy application source code
COPY src ./src

# Build the application JAR (skip tests for Docker build)
RUN mvn clean package -DskipTests -B

# ============================================================
# Stage 2: Runtime
# ============================================================
FROM eclipse-temurin:8-jdk-alpine

# Set timezone
ENV TZ=UTC

# Create non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Set ownership
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Application port
EXPOSE 8080

# JVM memory and container-awareness settings
ENV JAVA_OPTS="-Xmx512m -Xms256m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UnlockExperimentalVMOptions \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring profile
ENV SPRING_PROFILES_ACTIVE=docker

# Graceful shutdown via SIGTERM
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
