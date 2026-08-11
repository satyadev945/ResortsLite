package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration for Amazon ElastiCache — cr-java-0067 fix.
 *
 * <p>Provides a {@link RedisTemplate}{@code <String, Object>} bean used by
 * {@link BookingController} to cache booking entries in Amazon ElastiCache for Redis
 * with an explicit TTL, replacing the former unbounded in-memory {@code HashMap}.</p>
 *
 * <p>Serialization strategy:
 * <ul>
 *   <li>Keys are serialized as plain UTF-8 strings for human-readable Redis key names.</li>
 *   <li>Values are serialized as JSON using {@link GenericJackson2JsonRedisSerializer}
 *       so that any {@code Map<String, Object>} (or other POJO) can be stored and
 *       retrieved without loss of type information.</li>
 * </ul>
 * </p>
 *
 * <p>The {@link RedisConnectionFactory} is auto-configured by Spring Boot using the
 * {@code spring.redis.host} and {@code spring.redis.port} properties, which are
 * resolved from the {@code REDIS_HOST} and {@code REDIS_PORT} environment variables
 * pointing to the Amazon ElastiCache for Redis primary endpoint.</p>
 */
@Configuration
public class RedisConfig {

    /**
     * Creates a {@link RedisTemplate} configured with:
     * <ul>
     *   <li>String key serializer — keeps Redis keys readable in the ElastiCache console.</li>
     *   <li>JSON value serializer — stores booking maps as JSON, enabling cross-language
     *       cache consumers and human-readable inspection via {@code redis-cli}.</li>
     * </ul>
     *
     * @param connectionFactory the auto-configured Lettuce connection factory pointing
     *                          to Amazon ElastiCache for Redis
     * @return a fully configured {@link RedisTemplate} for booking cache operations
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use plain String serializer for keys so cache entries are human-readable
        // in the ElastiCache console and via redis-cli (e.g. "resortslite:booking:BK-1A2B3C4D").
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Use Jackson JSON serializer for values — supports Map<String, Object> and
        // other complex types without requiring a fixed schema.
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
