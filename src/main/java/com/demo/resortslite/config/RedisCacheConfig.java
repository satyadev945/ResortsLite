package com.demo.resortslite.config;

import org.springframework.beans.factory.annotation.Value;
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
 * FIXED cr-java-0067 [Cloud Compatibility / Mandatory]: In-Memory Caching Without TTL
 * 
 * This configuration replaces unbounded in-memory caching with Amazon ElastiCache for Redis
 * with proper TTL (Time-To-Live) policies. Benefits:
 * - Controlled cache expiration prevents indefinite memory growth
 * - Consistent cache data across all application instances in the cluster
 * - Centralized cache management via ElastiCache
 * - Automatic eviction of stale data based on TTL
 * - High availability through ElastiCache replication
 * 
 * Cache Configuration:
 * - Default TTL: 3600 seconds (1 hour) - configurable via application.properties
 * - Booking cache TTL: 1800 seconds (30 minutes) - shorter TTL for transactional data
 * - Serialization: JSON format for human-readable cache entries
 * - Key prefix: Automatic cache name prefix for namespace isolation
 * 
 * AWS ElastiCache Setup:
 * 1. Use the same ElastiCache Redis cluster configured for session management
 * 2. No additional infrastructure required - cache and session share the same Redis instance
 * 3. Redis connection configured via RedisSessionConfig and application.properties
 * 
 * Usage in Application Code:
 * - Annotate methods with @Cacheable("bookingCache") to enable caching
 * - Annotate methods with @CacheEvict to remove cache entries
 * - Annotate methods with @CachePut to update cache entries
 * - Spring automatically manages cache operations via Redis
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    @Value("${spring.cache.redis.time-to-live:3600}")
    private long defaultTtlSeconds;

    @Value("${spring.cache.redis.booking-ttl:1800}")
    private long bookingCacheTtlSeconds;

    /**
     * Configure Redis-backed cache manager with TTL policies
     * 
     * This replaces in-memory caching with Amazon ElastiCache for Redis.
     * All cache operations are now distributed across application instances
     * and automatically expire based on configured TTL values.
     * 
     * @param redisConnectionFactory Redis connection factory (shared with session management)
     * @return CacheManager configured for Redis with TTL
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory redisConnectionFactory) {
        // Default cache configuration with 1-hour TTL
        RedisCacheConfiguration defaultCacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(defaultTtlSeconds))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer()
                        )
                )
                .disableCachingNullValues();

        // Booking-specific cache configuration with 30-minute TTL
        RedisCacheConfiguration bookingCacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(bookingCacheTtlSeconds))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer()
                        )
                )
                .disableCachingNullValues();

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(defaultCacheConfig)
                .withCacheConfiguration("bookingCache", bookingCacheConfig)
                .transactionAware()
                .build();
    }
}
