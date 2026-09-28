package com.demo.resortslite.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Redis Configuration for Externalized Session Storage and Distributed Caching
 * 
 * This configuration enables:
 * 1. Spring Session with Redis backend to replace in-memory HttpSession storage
 * 2. Distributed caching using RedisTemplate for application-level caching
 * 
 * Session data and cache data are stored in Amazon ElastiCache (Redis) which allows:
 * - Horizontal scaling across multiple container instances
 * - Session and cache persistence across container restarts
 * - Shared state in EKS cluster environments
 * 
 * Redis connection details are configured via environment variables:
 * - REDIS_HOST: Redis server hostname (default: localhost)
 * - REDIS_PORT: Redis server port (default: 6379)
 * - REDIS_PASSWORD: Redis authentication password (optional)
 * 
 * For AWS EKS deployment with ElastiCache:
 * - Use IRSA (IAM Roles for Service Accounts) for secure access
 * - Configure Redis endpoint via ConfigMap or Secrets
 * - Enable Redis AUTH for production environments
 * 
 * FIXED cz-java-0070: Added RedisTemplate bean for distributed caching
 * Replaces local HashMap caches with Redis-backed distributed cache
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
     * Redis Connection Factory for connecting to ElastiCache Redis
     * Connection details are injected via environment variables
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisHost);
        config.setPort(redisPort);
        
        // Set password only if provided (not empty)
        if (redisPassword != null && !redisPassword.trim().isEmpty()) {
            config.setPassword(redisPassword);
        }
        
        return new LettuceConnectionFactory(config);
    }
    
    /**
     * RedisTemplate for distributed caching operations
     * 
     * FIXED cz-java-0070: Provides RedisTemplate bean for replacing local caches
     * with distributed Redis cache. This enables horizontal scaling by ensuring
     * cache data is shared across all container instances.
     * 
     * Serialization:
     * - Keys: StringRedisSerializer for human-readable keys
     * - Values: GenericJackson2JsonRedisSerializer for JSON serialization
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Use String serializer for keys
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        
        // Use JSON serializer for values
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        
        template.afterPropertiesSet();
        return template;
    }
}
