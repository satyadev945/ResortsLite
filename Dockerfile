# =============================================================================
# ResortsLite - Multi-Stage Dockerfile
# Framework : Spring Boot 2.7.18
# Java      : 8 (eclipse-temurin)
# Build Tool: Maven 3.9.4
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1: Builder
# ---------------------------------------------------------------------------
FROM maven:3.9.4-eclipse-temurin-8 AS builder

WORKDIR /workspace

# Copy dependency descriptor first for layer caching
COPY pom.xml .

# Download all dependencies (cached layer unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy the full source tree
COPY src ./src

# Build the fat JAR (skip tests — tests run in CI pipeline)
RUN mvn clean package -DskipTests -B

# ---------------------------------------------------------------------------
# Stage 2: Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:8-jre

# Timezone & locale
ENV TZ=UTC \
    LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8

# JVM container-aware memory settings
ENV JAVA_OPTS="-Xms256m -Xmx512m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UnlockExperimentalVMOptions \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8"

# Spring profile for containerised deployments
ENV SPRING_PROFILES_ACTIVE=docker

# Application port (matches server.port in application.properties)
EXPOSE 8080

# Create a non-root user for security
RUN groupadd --system appgroup && \
    useradd --system --gid appgroup --no-create-home appuser

WORKDIR /app

# Copy the fat JAR from the builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Ensure the non-root user owns the application files
RUN chown -R appuser:appgroup /app

USER appuser

# Graceful shutdown: Spring Boot honours SIGTERM by default
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
