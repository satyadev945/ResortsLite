package com.demo.resortslite.config;

// cz-java-0063: Externalize HTTP sessions to Amazon ElastiCache (Redis) via Spring Session.
// This replaces in-memory HttpSession with a Redis-backed distributed session store,
// ensuring session data is shared across all container replicas on EKS and survives
// container restarts and horizontal scaling events.
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Redis-backed HTTP session configuration (cz-java-0063).
 *
 * <p>Activating {@code @EnableRedisHttpSession} transparently replaces the default
 * in-memory {@link javax.servlet.http.HttpSession} implementation with a Spring Session
 * store backed by Amazon ElastiCache for Redis.  No changes to controller code are
 * required — existing {@code HttpSession} injection points continue to work, but all
 * session data is now persisted in Redis and is therefore visible to every pod in the
 * EKS cluster.
 *
 * <p>Connection details are supplied via environment variables (see
 * {@code application.properties}):
 * <ul>
 *   <li>{@code REDIS_HOST} — ElastiCache primary endpoint (default: {@code localhost})</li>
 *   <li>{@code REDIS_PORT} — Redis port (default: {@code 6379})</li>
 *   <li>{@code REDIS_PASSWORD} — Redis AUTH token (default: empty)</li>
 * </ul>
 */
@Configuration
@EnableRedisHttpSession
public class RedisSessionConfig {
    // Spring Session auto-configures the RedisConnectionFactory from
    // spring.redis.* properties, which are bound to environment variables
    // in application.properties.  No additional beans are required here.
}
