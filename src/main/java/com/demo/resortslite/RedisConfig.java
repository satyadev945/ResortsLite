package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis Configuration for Distributed Caching
 * 
 * FIXED cz-java-0070: Local Caches
 * 
 * This configuration enables Redis-backed distributed caching using Google Cloud
 * Memorystore for Redis. It replaces local in-memory caches with a shared cache
 * that works across all GKE pod replicas, enabling:
 * 
 * 1. Horizontal Scaling: All pods share the same cache state
 * 2. High Availability: Cache survives pod restarts and failures
 * 3. Load Balancing: Requests can be routed to any pod without cache misses
 * 4. TTL Support: Automatic cache expiration prevents stale data
 * 
 * Configuration:
 * - RedisTemplate configured with JSON serialization for complex objects
 * - Redis connection details are configured in application.properties
 * - Credentials managed via GKE Workload Identity and Secret Manager
 * 
 * Environment Variables Required:
 * - REDIS_HOST: Redis server hostname (Google Cloud Memorystore endpoint)
 * - REDIS_PORT: Redis server port (default: 6379)
 * - REDIS_PASSWORD: Redis authentication password (optional, from Secret Manager)
 */
@Configuration
public class RedisConfig {

    /**
     * Configure RedisTemplate for distributed caching
     * 
     * Uses JSON serialization to store complex objects (Maps, POJOs) in Redis.
     * This allows caching of booking data and other complex structures.
     * 
     * @param connectionFactory Redis connection factory (auto-configured by Spring Boot)
     * @return Configured RedisTemplate for caching operations
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Use String serializer for keys (e.g., "booking:cache:BK-12345678")
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        
        // Use JSON serializer for values (supports Maps, Lists, POJOs)
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        
        template.afterPropertiesSet();
        return template;
    }
}
