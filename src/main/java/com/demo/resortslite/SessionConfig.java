package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cz-java-0069 FIX: Spring Session configuration backed by Amazon ElastiCache for Redis.
 *
 * <p>Replaces the default in-memory HttpSession store with a Redis-backed session repository
 * (RedisIndexedSessionRepository). Spring Session's SessionRepositoryFilter intercepts every
 * HTTP request and transparently delegates all HttpSession operations to Redis, so:
 * <ul>
 *   <li>Sessions survive container/pod restarts — data lives in ElastiCache, not JVM heap.</li>
 *   <li>All EKS pod replicas share the same session store — horizontal scaling works correctly.</li>
 *   <li>AWS ALB sticky sessions are no longer required for session affinity.</li>
 * </ul>
 *
 * <p>Redis connection details are supplied at runtime via environment variables injected
 * through a Kubernetes Secret or ConfigMap:
 * <ul>
 *   <li>{@code SPRING_REDIS_HOST} — ElastiCache primary endpoint (e.g. my-cluster.abc.0001.use1.cache.amazonaws.com)</li>
 *   <li>{@code SPRING_REDIS_PORT} — Redis port (default 6379)</li>
 * </ul>
 *
 * <p>Session TTL is set to 1800 seconds (30 minutes) via {@code maxInactiveIntervalInSeconds}.
 * Override by setting {@code spring.session.timeout} in application.properties or as an env var.
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class SessionConfig {

    /**
     * cz-java-0069 FIX: Lettuce-based Redis connection factory.
     *
     * <p>Host and port are resolved from environment variables
     * {@code SPRING_REDIS_HOST} and {@code SPRING_REDIS_PORT} via Spring Boot's
     * auto-configuration (spring.redis.host / spring.redis.port properties).
     * No hardcoded values are used here — all connection details come from the
     * Kubernetes deployment manifest at runtime, pointing to Amazon ElastiCache for Redis.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory(
            @org.springframework.beans.factory.annotation.Value("${spring.redis.host:localhost}") String redisHost,
            @org.springframework.beans.factory.annotation.Value("${spring.redis.port:6379}") int redisPort) {
        return new LettuceConnectionFactory(redisHost, redisPort);
    }
}
