package com.demo.resortslite;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cz-java-0069: Spring Session Redis Configuration
 *
 * Replaces the default in-memory HttpSession with a Redis-backed session store
 * powered by Amazon ElastiCache for Redis on EKS.
 *
 * Why this fixes cz-java-0069 (In-Memory Session Storage):
 *   - Default HttpSession stores session data in JVM heap (in-memory).
 *   - When containers restart or new EKS pod replicas are created, all in-memory
 *     session data is lost, breaking user experience (e.g., lost booking state).
 *   - @EnableRedisHttpSession transparently replaces the in-memory HttpSession
 *     implementation with a Redis-backed store via spring-session-data-redis.
 *   - All session.setAttribute / session.getAttribute calls in BookingController
 *     are now delegated to Amazon ElastiCache for Redis, making session state
 *     durable across pod restarts and consistent across all EKS pod replicas.
 *
 * EKS Deployment:
 *   - REDIS_HOST and REDIS_PORT are injected as environment variables in the
 *     EKS Pod spec (via ConfigMap or AWS Secrets Manager sidecar).
 *   - IRSA (IAM Roles for Service Accounts) is used for secure access to
 *     Amazon ElastiCache for Redis without hardcoded credentials.
 *   - SESSION_TIMEOUT_SECONDS controls session TTL (default: 1800 seconds / 30 min).
 */
@Configuration
@EnableRedisHttpSession
public class SessionConfig {
    // Spring Session auto-configures the Redis connection using:
    //   spring.redis.host  -> ${REDIS_HOST:localhost}
    //   spring.redis.port  -> ${REDIS_PORT:6379}
    //   spring.redis.password -> ${REDIS_PASSWORD:}
    //   spring.session.timeout -> ${SESSION_TIMEOUT_SECONDS:1800}
    // All values are externalised via environment variables in application.properties.
}
