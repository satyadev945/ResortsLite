package com.demo.resortslite.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cr-java-0065 FIX: Redis-backed HTTP Session configuration using Amazon ElastiCache.
 *
 * <p>This class activates Spring Session Data Redis via {@code @EnableRedisHttpSession},
 * which transparently replaces the default in-process JVM {@code HttpSession} store with
 * a distributed session store backed by Amazon ElastiCache for Redis.  All
 * {@code HttpSession.setAttribute} and {@code HttpSession.getAttribute} calls in
 * {@link com.demo.resortslite.BookingController} are automatically intercepted and
 * redirected to the shared Redis cluster without any changes to the controller code.</p>
 *
 * <h3>Cloud-native benefits</h3>
 * <ul>
 *   <li>Session data is stored centrally in ElastiCache — visible to every EC2 instance
 *       behind the AWS Application Load Balancer (ALB).</li>
 *   <li>Eliminates server affinity (sticky sessions) requirements on the ALB.</li>
 *   <li>Supports horizontal auto-scaling: new instances immediately share all active
 *       sessions without warm-up or replication delays.</li>
 *   <li>Prevents session data loss on instance termination or failover.</li>
 *   <li>Session TTL is enforced by Redis, preventing unbounded memory growth.</li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * Set the following environment variables (or AWS SSM / Secrets Manager values) in each
 * deployment environment:
 * <pre>
 *   REDIS_HOST     — ElastiCache primary endpoint hostname (default: localhost)
 *   REDIS_PORT     — ElastiCache port                      (default: 6379)
 *   REDIS_PASSWORD — ElastiCache AUTH token                (default: empty)
 *   SESSION_TIMEOUT_SECONDS — session TTL in seconds       (default: 1800 = 30 min)
 * </pre>
 *
 * <p>For local development without an ElastiCache cluster, start a local Redis instance
 * ({@code docker run -p 6379:6379 redis:7-alpine}) and leave the defaults.</p>
 */
@Configuration
// cr-java-0065 FIX: @EnableRedisHttpSession activates Spring Session Data Redis.
// maxInactiveIntervalInSeconds = 1800 (30 minutes) — override at runtime via the
// SESSION_TIMEOUT_SECONDS environment variable by setting:
//   spring.session.timeout=<seconds>s  in application.properties, or
//   SERVER_SERVLET_SESSION_TIMEOUT env var (Spring Boot auto-configuration).
// The default 1800 s is a safe production value; adjust per security policy.
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    /**
     * ElastiCache primary endpoint hostname.
     * Set via {@code REDIS_HOST} environment variable.
     * Defaults to {@code localhost} for local development.
     */
    @Value("${REDIS_HOST:localhost}")
    private String redisHost;

    /**
     * ElastiCache port.
     * Set via {@code REDIS_PORT} environment variable.
     * Defaults to {@code 6379}.
     */
    @Value("${REDIS_PORT:6379}")
    private int redisPort;

    /**
     * ElastiCache AUTH token (password).
     * Set via {@code REDIS_PASSWORD} environment variable.
     * Leave empty for clusters without AUTH (not recommended for production).
     */
    @Value("${REDIS_PASSWORD:}")
    private String redisPassword;

    /**
     * Creates a Lettuce-based {@link RedisConnectionFactory} pointing at the Amazon
     * ElastiCache cluster configured via environment variables.
     *
     * <p>Lettuce is the recommended non-blocking Redis client for Spring Boot 2.x and
     * is included transitively by {@code spring-session-data-redis}.</p>
     *
     * @return configured {@link LettuceConnectionFactory}
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.setPassword(redisPassword);
        }
        return new LettuceConnectionFactory(config);
    }
}
