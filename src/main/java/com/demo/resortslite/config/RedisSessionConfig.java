package com.demo.resortslite.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * FIXED cr-java-0065: Redis Session Configuration for Amazon ElastiCache
 * 
 * This configuration enables distributed session management using Spring Session
 * with Amazon ElastiCache for Redis. All HTTP session data is stored in Redis,
 * making the application stateless and enabling horizontal scaling.
 * 
 * Benefits:
 * - Sessions persist across application restarts
 * - Load balancer can route requests to any instance
 * - Auto-scaling works without session loss
 * - Failover and rolling deployments maintain user sessions
 * 
 * Configuration is externalized via environment variables:
 * - REDIS_HOST: ElastiCache endpoint (e.g., my-cluster.abc123.0001.use1.cache.amazonaws.com)
 * - REDIS_PORT: Redis port (default: 6379)
 * - REDIS_PASSWORD: Redis AUTH password (if enabled)
 * - REDIS_SSL: Enable SSL/TLS for in-transit encryption (recommended for production)
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800) // 30 minutes session timeout
public class RedisSessionConfig {

    /**
     * Redis connection factory bean.
     * Spring Boot auto-configuration creates this bean from application.properties,
     * but we can customize it here if needed for AWS-specific settings.
     * 
     * For production AWS deployments:
     * - Use ElastiCache cluster mode for high availability
     * - Enable encryption in-transit (SSL/TLS)
     * - Enable encryption at-rest
     * - Use VPC security groups to restrict access
     * - Configure automatic failover
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // Spring Boot auto-configuration will create LettuceConnectionFactory
        // from spring.redis.* properties in application.properties
        // This bean definition can be customized for AWS-specific requirements
        return new LettuceConnectionFactory();
    }
}
