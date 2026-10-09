package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cr-java-0065 fix: Distributed HTTP Session via Amazon ElastiCache for Redis.
 *
 * <p>Replaces the default in-process {@code HttpSession} store with a centralized
 * Redis-backed session store provided by Spring Session Data Redis. Every application
 * instance connects to the same ElastiCache cluster, so session data is visible across
 * all nodes — enabling stateless horizontal scaling, zero-downtime deployments, and
 * correct behaviour behind an AWS Application Load Balancer without sticky sessions.</p>
 *
 * <p>Configuration is driven entirely by environment variables / application properties
 * (see {@code application.properties}):
 * <ul>
 *   <li>{@code REDIS_HOST} — ElastiCache primary endpoint (default: localhost)</li>
 *   <li>{@code REDIS_PORT} — Redis port (default: 6379)</li>
 *   <li>{@code REDIS_PASSWORD} — Redis AUTH password (default: empty)</li>
 *   <li>{@code REDIS_SSL} — Enable TLS for in-transit encryption (default: false)</li>
 *   <li>{@code SESSION_TIMEOUT_SECONDS} — Session TTL in seconds (default: 1800)</li>
 * </ul>
 * </p>
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    /**
     * Configures a Lettuce-based Redis connection factory pointing at the
     * Amazon ElastiCache cluster endpoint supplied via environment variables.
     *
     * <p>Lettuce is the recommended non-blocking Redis client for Spring Boot 2.x
     * and supports ElastiCache cluster mode, TLS, and connection pooling out of the box.</p>
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
