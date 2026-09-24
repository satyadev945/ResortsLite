package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cz-java-0063 FIX: Spring Session Redis Configuration
 *
 * Replaces the default in-memory HttpSession store with a Redis-backed session store
 * using Google Cloud Memorystore for Redis on GKE Autopilot.
 *
 * - Redis host/port/password are injected via environment variables managed through
 *   GKE Workload Identity and Secret Manager (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD).
 * - @EnableRedisHttpSession transparently intercepts all HttpSession operations and
 *   delegates them to Redis, making sessions durable across container restarts and
 *   consistent across all horizontally-scaled pod replicas.
 * - maxInactiveIntervalInSeconds controls session TTL in Redis (default: 1800s / 30 min).
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class SessionConfig {

    /**
     * Builds a Lettuce-based Redis connection factory using environment variables
     * injected by GKE Workload Identity / Secret Manager:
     *   REDIS_HOST     – Memorystore instance IP or hostname (required)
     *   REDIS_PORT     – Memorystore port, defaults to 6379
     *   REDIS_PASSWORD – Auth token / password (optional, leave blank if auth disabled)
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        String redisPassword = System.getenv().getOrDefault("REDIS_PASSWORD", "");

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.setPassword(redisPassword);
        }

        return new LettuceConnectionFactory(config);
    }
}
