package com.demo.resortslite.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.beans.factory.annotation.Value;

/**
 * Redis Session Configuration for Amazon ElastiCache
 * 
 * FIXED cr-java-0065: HTTP Session State Storage
 * 
 * This configuration enables Spring Session with Redis backend, replacing in-memory
 * HTTP session storage with Amazon ElastiCache for Redis. This provides:
 * 
 * 1. Stateless application instances - sessions are stored externally in Redis
 * 2. Horizontal scalability - AWS ALB can route requests to any EC2 instance
 * 3. High availability - ElastiCache provides automatic failover and replication
 * 4. Session persistence - survives application restarts and auto-scaling events
 * 
 * Production Deployment:
 * - Create Amazon ElastiCache for Redis cluster in AWS
 * - Configure cluster endpoint via REDIS_HOST environment variable
 * - Enable encryption in-transit and set REDIS_PASSWORD if required
 * - Use ElastiCache cluster mode for multi-AZ high availability
 * 
 * Session timeout is configured in application.properties:
 * spring.session.timeout=1800s (30 minutes default)
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    /**
     * Configure Redis connection factory for Amazon ElastiCache
     * 
     * In production, this connects to ElastiCache cluster endpoint.
     * Lettuce client provides connection pooling and automatic reconnection.
     * 
     * @return RedisConnectionFactory configured for ElastiCache
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redisHost, redisPort);
        factory.afterPropertiesSet();
        return factory;
    }
}
