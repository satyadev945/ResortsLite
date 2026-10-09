package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * cz-java-0070: Redis configuration for Amazon ElastiCache distributed cache.
 *
 * Provides a RedisTemplate<String, Object> bean used by BookingController to store
 * booking cache entries in Amazon ElastiCache (Redis) instead of a local in-memory
 * HashMap. This ensures cache data is shared across all EKS pod replicas and supports
 * TTL-based expiry to prevent stale or unbounded cache growth.
 *
 * Connection details (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD) are injected via
 * Kubernetes ConfigMap and Secrets with IRSA-secured access to ElastiCache.
 */
@Configuration
public class RedisConfig {

    /**
     * cz-java-0070: Configures a RedisTemplate for distributed booking cache backed by
     * Amazon ElastiCache (Redis). Uses String keys and JSON-serialized Object values
     * for human-readable, type-safe cache entries compatible with ElastiCache.
     *
     * @param connectionFactory auto-configured by spring-boot-starter-data-redis using
     *                          REDIS_HOST / REDIS_PORT / REDIS_PASSWORD env vars from
     *                          Kubernetes ConfigMap/Secret.
     * @return RedisTemplate<String, Object> for use in BookingController cache operations
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        // cz-java-0070: String serializer for cache keys (e.g. "booking:BK-XXXXXXXX")
        template.setKeySerializer(new StringRedisSerializer());
        // cz-java-0070: JSON serializer for cache values — preserves type info for Map<String, Object>
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
