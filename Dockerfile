# ---------- Builder stage ----------
FROM maven:3.9.4-eclipse-temurin-8 AS builder
WORKDIR /workspace

# Cache dependencies first
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# ---------- Runtime stage ----------
FROM eclipse-temurin:8-jdk-alpine
WORKDIR /app

ENV TZ=UTC \
    LANG=C.UTF-8 \
    SPRING_PROFILES_ACTIVE=docker \
    SERVER_PORT=8080 \
    REPORT_BASE_PATH=/app/reports/ \
    BACKUP_PATH=/app/backups/ \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Xms256m -Dfile.encoding=UTF-8 -Duser.timezone=UTC -Djava.security.egd=file:/dev/./urandom"

RUN addgroup -S appgroup && adduser -S appuser -G appgroup \
    && mkdir -p /app/reports /app/backups /app/config \
    && chown -R appuser:appgroup /app

COPY --from=builder --chown=appuser:appgroup /workspace/target/*.jar /app/app.jar

USER appuser

EXPOSE 8080

# exec form via sh so signals (SIGTERM) reach the JVM for graceful shutdown
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
