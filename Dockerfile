FROM maven:3.9.4-eclipse-temurin-8 AS builder
WORKDIR /workspace

# Cache dependencies first
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Build application
COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:8-jdk-alpine
WORKDIR /app

ENV TZ=UTC \
    LANG=C.UTF-8 \
    SPRING_PROFILES_ACTIVE=docker \
    SERVER_PORT=8080 \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Djava.security.egd=file:/dev/./urandom"

RUN addgroup -S appgroup && adduser -S appuser -G appgroup \
    && mkdir -p /app/reports /app/backups /app/config \
    && chown -R appuser:appgroup /app

COPY --from=builder --chown=appuser:appgroup /workspace/target/*.jar /app/app.jar

USER appuser
EXPOSE 8080

# exec form with sh -c so JAVA_OPTS expand and java receives SIGTERM (PID 1) for graceful shutdown
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
