package com.demo.resortslite;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * cr-java-0067 FIX: Redis configuration for distributed booking cache.
 *
 * Configures a RedisTemplate&lt;String, Object&gt; bean that serializes keys as plain
 * UTF-8 strings and values as JSON (via GenericJackson2JsonRedisSerializer). This
 * replaces the in-memory HashMap bookingCache in BookingController with an Azure Cache
 * for Redis-backed store that:
 *   - Supports TTL-based expiry to prevent indefinite memory growth
 *   - Is shared across all application instances (no per-instance inconsistency)
 *   - Survives instance restarts without data loss (within the TTL window)
 */
@Configuration
public class RedisConfig {

    /**
     * Provides a RedisTemplate configured with:
     *   - StringRedisSerializer for keys  (human-readable keys in Redis CLI)
     *   - GenericJackson2JsonRedisSerializer for values (type-safe JSON round-trip)
     *
     * The ObjectMapper is configured with default typing so that Map&lt;String, Object&gt;
     * values can be deserialized back to their original types without explicit type hints
     * at the call site.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
