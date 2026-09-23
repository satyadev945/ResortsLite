package com.demo.resortslite.config;

// cz-java-0070: RedisTemplate configuration for Amazon ElastiCache (Redis) distributed cache.
// This bean replaces the former local in-memory HashMap (bookingCache) in BookingController
// with a distributed cache backed by Amazon ElastiCache for Redis, enabling safe horizontal
// scaling across EKS pod replicas. Connection details are injected via environment variables
// (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD) through Kubernetes ConfigMaps and Secrets.
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis cache configuration (cz-java-0070).
 *
 * <p>Provides a {@link RedisTemplate} bean with String keys and JSON-serialised Object
 * values, used by {@code BookingController} to store booking entries in Amazon ElastiCache
 * instead of the former local {@code HashMap} cache.  Using a distributed cache ensures
 * that all EKS pod replicas share the same cache state, eliminating the cache-miss
 * inconsistency that occurs when requests are load-balanced across multiple instances.
 *
 * <p>The {@link RedisConnectionFactory} is auto-configured by Spring Boot from the
 * {@code spring.redis.*} properties, which are bound to environment variables:
 * <ul>
 *   <li>{@code REDIS_HOST} — ElastiCache primary endpoint (default: {@code localhost})</li>
 *   <li>{@code REDIS_PORT} — Redis port (default: {@code 6379})</li>
 *   <li>{@code REDIS_PASSWORD} — Redis AUTH token (default: empty)</li>
 * </ul>
 */
@Configuration
public class RedisCacheConfig {

    /**
     * RedisTemplate configured with String key serializer and JSON value serializer.
     * Used by BookingController to cache booking objects in Amazon ElastiCache (Redis).
     *
     * @param connectionFactory auto-configured by Spring Boot from spring.redis.* properties
     * @return configured RedisTemplate instance
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serializer for keys to keep Redis keys human-readable
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Use Jackson JSON serializer for values to support arbitrary Object types
        Jackson2JsonRedisSerializer<Object> jsonSerializer = new Jackson2JsonRedisSerializer<>(Object.class);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL
        );
        jsonSerializer.setObjectMapper(objectMapper);

        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();

        return template;
    }
}
