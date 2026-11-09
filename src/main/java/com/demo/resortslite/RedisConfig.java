package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * cr-java-0067 FIX: Redis Cache Configuration for Google Cloud Memorystore for Redis
 *
 * <p>Configures a {@link RedisTemplate} bean with JSON serialization so that
 * {@code Map<String, Object>} booking cache entries can be stored and retrieved
 * correctly across all application instances connected to the same
 * Google Cloud Memorystore for Redis instance.</p>
 *
 * <p><strong>Serialization strategy:</strong></p>
 * <ul>
 *   <li>Keys are serialized as plain UTF-8 strings (e.g., {@code booking:cache:BK-A1B2C3D4}).</li>
 *   <li>Values are serialized as JSON using {@link GenericJackson2JsonRedisSerializer},
 *       which embeds type metadata so that deserialization on any instance produces the
 *       correct Java type without requiring a shared class registry.</li>
 * </ul>
 *
 * <p><strong>TTL enforcement:</strong></p>
 * <p>TTL is applied per entry in {@link BookingController} via
 * {@code redisTemplate.opsForValue().set(key, value, ttl, TimeUnit.SECONDS)}.
 * Redis automatically expires entries after the configured TTL, preventing
 * unbounded memory growth in the Memorystore instance.</p>
 *
 * <p><strong>Runtime configuration (application.properties / environment variables):</strong></p>
 * <pre>
 *   spring.redis.host=${REDIS_HOST:localhost}   # Memorystore for Redis IP
 *   spring.redis.port=${REDIS_PORT:6379}
 *   booking.cache.ttl=${BOOKING_CACHE_TTL:3600} # Cache entry TTL in seconds
 * </pre>
 */
@Configuration
public class RedisConfig {

    /**
     * Configures a {@link RedisTemplate} with:
     * <ul>
     *   <li>{@link StringRedisSerializer} for keys — human-readable key names in Redis.</li>
     *   <li>{@link GenericJackson2JsonRedisSerializer} for values — JSON serialization
     *       with embedded type information, enabling cross-instance deserialization of
     *       {@code Map<String, Object>} booking cache entries.</li>
     * </ul>
     *
     * @param connectionFactory auto-configured by Spring Boot from {@code spring.redis.*}
     *                          properties pointing to Google Cloud Memorystore for Redis.
     * @return configured {@link RedisTemplate} used by {@link BookingController}.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use plain string serializer for keys so they are human-readable in Redis CLI
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Use Jackson JSON serializer for values — supports Map<String, Object> and
        // preserves type information for correct deserialization on any instance
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
