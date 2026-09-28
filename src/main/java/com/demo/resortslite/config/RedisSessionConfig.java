package com.demo.resortslite.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Redis Session Configuration for Amazon ElastiCache
 * 
 * FIXED cr-java-0065 [Cloud Compatibility / Mandatory]: HTTP Session State Storage
 * 
 * This configuration enables distributed session management using Amazon ElastiCache for Redis
 * via Spring Session. All HTTP session data is now stored in Redis instead of in-memory,
 * enabling:
 * - Stateless application instances that can scale horizontally
 * - Session persistence across instance restarts and auto-scaling events
 * - Session sharing across multiple EC2 instances behind AWS ALB
 * - High availability through ElastiCache replication
 * 
 * Configuration:
 * - Session timeout: 1800 seconds (30 minutes)
 * - Redis connection: Configured via application.properties
 * - ElastiCache endpoint: Set via SPRING_REDIS_HOST environment variable
 * 
 * AWS ElastiCache Setup:
 * 1. Create ElastiCache Redis cluster in AWS Console
 * 2. Note the primary endpoint (e.g., my-redis.abc123.0001.use1.cache.amazonaws.com:6379)
 * 3. Set environment variable: SPRING_REDIS_HOST=<endpoint-without-port>
 * 4. Ensure security group allows inbound traffic on port 6379 from application instances
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800) // 30 minutes session timeout
public class RedisSessionConfig {

    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    @Value("${spring.redis.password:#{null}}")
    private String redisPassword;

    /**
     * Configure Redis connection factory for Amazon ElastiCache
     * 
     * In AWS cloud environment:
     * - Set SPRING_REDIS_HOST to ElastiCache primary endpoint hostname
     * - Set SPRING_REDIS_PORT if using non-standard port (default: 6379)
     * - Set SPRING_REDIS_PASSWORD if using AUTH (recommended for production)
     * 
     * For local development:
     * - Uses localhost:6379 by default
     * - Run Redis locally: docker run -d -p 6379:6379 redis:7-alpine
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(redisHost);
        redisConfig.setPort(redisPort);
        
        // Set password if provided (recommended for production ElastiCache)
        if (redisPassword != null && !redisPassword.isEmpty()) {
            redisConfig.setPassword(redisPassword);
        }
        
        return new LettuceConnectionFactory(redisConfig);
    }
}
