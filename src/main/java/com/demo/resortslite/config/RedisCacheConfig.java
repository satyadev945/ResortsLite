package com.demo.resortslite.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * FIXED cr-java-0067: Redis Cache Configuration for Amazon ElastiCache
 * 
 * This configuration replaces in-memory caching with Amazon ElastiCache for Redis
 * with proper TTL policies. This ensures:
 * - Controlled cache expiration prevents indefinite memory growth
 * - Consistent cache data across all application instances
 * - Centralized cache management in cloud environment
 * - Automatic cache eviction based on TTL
 * 
 * Benefits:
 * - Prevents out-of-memory errors from unbounded cache growth
 * - Eliminates stale data inconsistencies across instances
 * - Enables horizontal scaling with shared cache
 * - Supports cache invalidation and refresh strategies
 * 
 * Configuration is externalized via environment variables:
 * - REDIS_HOST: ElastiCache endpoint
 * - REDIS_PORT: Redis port (default: 6379)
 * - REDIS_PASSWORD: Redis AUTH password
 * - CACHE_TTL_MINUTES: Cache entry TTL (default: 30 minutes)
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    @Value("${cache.ttl.minutes:30}")
    private int cacheTtlMinutes;

    /**
     * Configure Redis Cache Manager with TTL policies
     * All cache entries will automatically expire after the configured TTL
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory redisConnectionFactory) {
        RedisCacheConfiguration cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(cacheTtlMinutes))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer())
                )
                .disableCachingNullValues();

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(cacheConfiguration)
                .transactionAware()
                .build();
    }

    /**
     * RedisTemplate for direct Redis operations
     * Used for manual cache operations when Spring Cache abstraction is not sufficient
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
