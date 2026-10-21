package com.demo.resortslite.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis Cache Configuration for Amazon ElastiCache
 * 
 * FIXED cr-java-0067: In-Memory Caching Without TTL
 * 
 * This configuration replaces unbounded in-memory caching with Amazon ElastiCache for Redis,
 * providing:
 * 
 * 1. Distributed caching - cache is shared across all application instances
 * 2. TTL-based expiration - automatic cache entry eviction prevents memory growth
 * 3. Horizontal scalability - consistent cache state across AWS ALB-distributed requests
 * 4. High availability - ElastiCache provides automatic failover and replication
 * 5. Memory management - Redis handles eviction policies (LRU, LFU) automatically
 * 
 * Production Deployment:
 * - Uses the same ElastiCache cluster as Spring Session (RedisSessionConfig)
 * - Cache entries expire after 30 minutes (configurable via TTL_MINUTES)
 * - JSON serialization enables complex object caching
 * - Separate cache namespaces prevent session/cache key collisions
 * 
 * Benefits over in-memory HashMap:
 * - No memory leaks from unbounded cache growth
 * - Cache consistency across multiple EC2/ECS/EKS instances
 * - Automatic cleanup of stale entries via TTL
 * - Survives application restarts (optional persistence)
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    /**
     * Default TTL for cache entries (30 minutes)
     * In production, externalize to application.properties:
     * spring.cache.redis.time-to-live=1800000
     */
    private static final long TTL_MINUTES = 30;

    /**
     * Configure Redis-based cache manager with TTL policies
     * 
     * This replaces Spring's default in-memory cache (ConcurrentMapCacheManager)
     * with Redis-backed distributed caching via Amazon ElastiCache.
     * 
     * @param redisConnectionFactory Redis connection factory (shared with session management)
     * @return CacheManager configured for ElastiCache with TTL
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory redisConnectionFactory) {
        // Configure cache entry serialization
        RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                // Set TTL to 30 minutes - entries automatically expire
                .entryTtl(Duration.ofMinutes(TTL_MINUTES))
                // Serialize cache keys as strings
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                // Serialize cache values as JSON (supports complex objects)
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer()))
                // Disable caching of null values
                .disableCachingNullValues();

        // Build Redis cache manager with TTL configuration
        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(cacheConfig)
                .transactionAware()
                .build();
    }
}
