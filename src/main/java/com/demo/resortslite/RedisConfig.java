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
 * <p>Configures a {@link RedisTemplate}{@code <String, Object>} bean that serializes
 * keys as plain UTF-8 strings and values as JSON (via Jackson).  This template is
 * injected into {@link BookingController} to replace the previous unbounded static
 * {@code HashMap<String, Object>} (bookingCache) with a shared, TTL-aware cache
 * stored in Amazon ElastiCache for Redis.
 *
 * <p>Key design decisions:
 * <ul>
 *   <li><b>StringRedisSerializer</b> for keys — human-readable keys in the format
 *       {@code booking:<bookingId>}, easy to inspect with redis-cli.</li>
 *   <li><b>Jackson2JsonRedisSerializer</b> for values — stores booking maps as JSON,
 *       enabling cross-language cache consumers and readable cache inspection.</li>
 *   <li><b>Default typing enabled</b> — allows Jackson to deserialize polymorphic
 *       {@code Object} values (e.g. {@code Map<String, Object>}) correctly.</li>
 * </ul>
 *
 * <p>The underlying {@link RedisConnectionFactory} is auto-configured by Spring Boot
 * using the {@code spring.redis.host} and {@code spring.redis.port} properties
 * (set to the Amazon ElastiCache primary endpoint in production).
 */
@Configuration
public class RedisConfig {

    /**
     * Configures a {@link RedisTemplate} with String keys and JSON-serialized Object
     * values for use with the Amazon ElastiCache for Redis booking cache.
     *
     * @param connectionFactory auto-configured Lettuce connection factory pointing to
     *                          the ElastiCache Redis endpoint
     * @return a fully configured {@link RedisTemplate}{@code <String, Object>}
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key serializer: plain UTF-8 string (e.g. "booking:BK-A1B2C3D4")
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value serializer: Jackson JSON with default typing for Map<String, Object>
        Jackson2JsonRedisSerializer<Object> jacksonSerializer =
                new Jackson2JsonRedisSerializer<>(Object.class);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // Enable default typing so Jackson can reconstruct Map<String, Object> on read.
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL
        );
        jacksonSerializer.setObjectMapper(objectMapper);

        template.setValueSerializer(jacksonSerializer);
        template.setHashValueSerializer(jacksonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
