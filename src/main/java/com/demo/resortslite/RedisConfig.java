package com.demo.resortslite;

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
 * cr-java-0067 FIX: Redis configuration for Amazon ElastiCache-backed booking cache.
 *
 * Provides a {@link RedisTemplate} bean typed as {@code RedisTemplate<String, Object>}
 * that serialises keys as plain UTF-8 strings and values as JSON via Jackson.
 * This replaces the unbounded static in-memory {@code HashMap} (bookingCache) that
 * previously existed in {@link BookingController}, which caused:
 * <ul>
 *   <li>Indefinite memory growth (no TTL / eviction policy)</li>
 *   <li>Stale data inconsistencies across multiple EC2 instances</li>
 *   <li>Cache isolation — each instance maintained its own private copy</li>
 * </ul>
 *
 * With this configuration all booking cache entries are stored in the shared
 * Amazon ElastiCache for Redis cluster, ensuring a single consistent cache view
 * across all Auto Scaling group members.  Each entry is written with a configurable
 * TTL (see {@code booking.cache.ttl-minutes} in application.properties) so that
 * stale entries are automatically evicted.
 *
 * Connection details are supplied via environment variables:
 * <ul>
 *   <li>{@code SPRING_REDIS_HOST} — ElastiCache primary endpoint hostname</li>
 *   <li>{@code SPRING_REDIS_PORT} — Redis port (default 6379)</li>
 *   <li>{@code SPRING_REDIS_PASSWORD} — Redis AUTH token (if cluster auth is enabled)</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    /**
     * Configures a {@link RedisTemplate} with:
     * <ul>
     *   <li>String key serializer — human-readable keys (e.g. {@code booking:BK-1A2B3C4D})</li>
     *   <li>Jackson JSON value serializer — type-safe serialisation of {@code Map<String, Object>}
     *       and other complex types stored in the booking cache</li>
     * </ul>
     *
     * @param connectionFactory auto-configured by Spring Boot from {@code spring.redis.*} properties
     * @return a fully configured {@link RedisTemplate} for booking cache operations
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key serializer: plain UTF-8 string (e.g. "booking:BK-1A2B3C4D")
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value serializer: Jackson JSON with type information embedded so that
        // Map<String, Object> round-trips correctly through Redis.
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL
        );

        Jackson2JsonRedisSerializer<Object> jsonSerializer =
                new Jackson2JsonRedisSerializer<>(Object.class);
        jsonSerializer.setObjectMapper(objectMapper);

        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
