# ---------- Build stage ----------
FROM maven:3.9.4-eclipse-temurin-8 AS builder
WORKDIR /workspace

# Cache dependencies first
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy sources and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# ---------- Runtime stage ----------
FROM openjdk:8-jdk

ENV TZ=UTC \
    LANG=C.UTF-8 \
    SPRING_PROFILES_ACTIVE=docker \
    SERVER_PORT=8080 \
    REPORT_BASE_PATH=/app/reports/ \
    BACKUP_PATH=/app/backups/ \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom"

WORKDIR /app

RUN groupadd -r appgroup && useradd -r -g appgroup -d /app appuser \
    && mkdir -p /app/reports /app/backups /app/config \
    && chown -R appuser:appgroup /app

COPY --from=builder --chown=appuser:appgroup /workspace/target/*.jar /app/app.jar

USER appuser

EXPOSE 8080

# exec form via sh so JAVA_OPTS is expanded and the JVM receives SIGTERM (graceful shutdown)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
