# ---------- Builder stage ----------
FROM maven:3.9.4-eclipse-temurin-8 AS builder
WORKDIR /workspace

# Cache dependencies first
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy sources and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# ---------- Runtime stage ----------
FROM amazoncorretto:8

ENV TZ=UTC \
    LANG=C.UTF-8 \
    SPRING_PROFILES_ACTIVE=docker \
    SERVER_PORT=8080 \
    REPORT_BASE_PATH=/app/reports/ \
    BACKUP_BASE_PATH=/app/backups/ \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Xms256m -Dfile.encoding=UTF-8 -Duser.timezone=UTC -Djava.security.egd=file:/dev/./urandom"

WORKDIR /app

RUN mkdir -p /app/reports /app/backups /app/config && chown -R 1001:1001 /app

COPY --from=builder --chown=1001:1001 /workspace/target/resortsLite-1.0.0.jar /app/app.jar

USER 1001:1001

EXPOSE 8080

# exec form via sh so JAVA_OPTS expands and java receives SIGTERM as PID 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
