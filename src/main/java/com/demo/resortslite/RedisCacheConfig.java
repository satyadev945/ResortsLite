package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * RedisCacheConfig — configures the {@link RedisTemplate} used by
 * {@link BookingController} to store booking cache entries in Amazon
 * ElastiCache for Redis.
 *
 * <p>cr-java-0067 fix: Replaces the unbounded in-memory {@code HashMap}
 * ({@code bookingCache}) with a shared, TTL-backed Redis cache. This
 * configuration bean provides a {@code RedisTemplate<String, Object>} that:
 * <ul>
 *   <li>Serializes keys as plain UTF-8 strings (prefixed with
 *       {@code "booking:"} by the controller).</li>
 *   <li>Serializes values as JSON using
 *       {@link GenericJackson2JsonRedisSerializer}, making entries
 *       human-readable in Redis and portable across JVM restarts.</li>
 *   <li>Connects to the Amazon ElastiCache Redis cluster whose endpoint is
 *       supplied at runtime via the {@code REDIS_HOST} / {@code REDIS_PORT}
 *       environment variables (see {@code application.properties}).</li>
 * </ul>
 * </p>
 *
 * <p>TTL enforcement is applied per-entry in {@link BookingController} via
 * {@code redisTemplate.opsForValue().set(key, value, ttl, TimeUnit.SECONDS)},
 * where the TTL is controlled by the {@code app.cache.booking.ttl-seconds}
 * property (default: 3600 s / 1 hour).</p>
 */
@Configuration
public class RedisCacheConfig {

    /**
     * Provides a {@link RedisTemplate} with JSON value serialization for
     * booking cache entries stored in Amazon ElastiCache for Redis.
     *
     * <p>The {@link RedisConnectionFactory} is auto-configured by Spring Boot
     * using the {@code spring.redis.host}, {@code spring.redis.port}, and
     * {@code spring.redis.ssl} properties defined in
     * {@code application.properties}.</p>
     *
     * @param connectionFactory Spring Boot auto-configured Redis connection factory
     * @return configured {@link RedisTemplate} for {@code String} keys and
     *         {@code Object} (JSON-serialized) values
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Keys are stored as plain UTF-8 strings (e.g. "booking:BK-A1B2C3D4")
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);

        // Values are stored as JSON for human readability and cross-JVM portability
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
