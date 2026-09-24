package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cr-java-0065: Redis Session Configuration for Amazon ElastiCache.
 *
 * Migrates HTTP session state from instance-local HttpSession to Amazon ElastiCache
 * for Redis using Spring Session. This enables stateless application instances that
 * can be horizontally scaled across multiple EC2 instances behind an AWS ALB without
 * requiring sticky sessions or server affinity.
 *
 * Configuration is driven entirely by environment variables, following 12-factor app
 * principles. Set REDIS_HOST and REDIS_PORT to point to your ElastiCache cluster
 * endpoint. SESSION_TTL_SECONDS controls the Redis key TTL for automatic expiry.
 *
 * AWS ElastiCache for Redis setup:
 *   - Create an ElastiCache Redis cluster in the same VPC as your EC2/ECS instances.
 *   - Set REDIS_HOST to the Primary Endpoint (e.g., my-cluster.abc123.ng.0001.use1.cache.amazonaws.com).
 *   - Set REDIS_PORT to 6379 (default) or your configured port.
 *   - Ensure the EC2/ECS security group allows outbound TCP on port 6379 to the ElastiCache SG.
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    // ElastiCache Primary Endpoint — injected from environment variable.
    // Default falls back to localhost for local development without Redis.
    @Value("${REDIS_HOST:localhost}")
    private String redisHost;

    // ElastiCache port — default 6379.
    @Value("${REDIS_PORT:6379}")
    private int redisPort;

    /**
     * Lettuce connection factory pointing to Amazon ElastiCache for Redis.
     * Lettuce is the recommended client for ElastiCache as it supports
     * cluster mode, TLS, and connection pooling out of the box.
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * RedisTemplate configured with JSON serialization for session attributes.
     * Keys are stored as plain strings; values are serialized as JSON using
     * Jackson so that complex objects (e.g., booking Maps) round-trip correctly.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate() {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory());
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
