# syntax=docker/dockerfile:1
# ---- Build stage: Maven on Java 8 ----
FROM maven:3.8.6-openjdk-8-slim AS builder
WORKDIR /workspace

# Resolve dependencies first so that this layer is cached
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Build the runnable Spring Boot jar (spring-boot-maven-plugin repackage)
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Runtime stage ----
FROM amazoncorretto:8

WORKDIR /app

ENV TZ=UTC \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    SPRING_PROFILES_ACTIVE=prod \
    APP_PORT=8080 \
    REPORT_PATH=/app/reports \
    BACKUP_PATH=/app/backups \
    LOGGING_LEVEL_ROOT=INFO \
    JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8 -Duser.timezone=UTC"

COPY --from=builder /workspace/target/*.jar app.jar

# Non-root user (numeric id) and the writable report and backup directories
RUN mkdir -p /app/reports /app/backups && chown -R 1001:1001 /app
USER 1001:1001

EXPOSE 8080

# exec so that java is the first process and receives SIGTERM for a graceful shutdown
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
