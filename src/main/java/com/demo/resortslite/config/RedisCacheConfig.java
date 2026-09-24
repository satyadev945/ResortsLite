package com.demo.resortslite.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * cr-java-0067 FIX: Amazon ElastiCache for Redis — booking cache configuration.
 *
 * <p>This class provides a {@link RedisTemplate}{@code <String, Object>} bean named
 * {@code bookingRedisTemplate} used by {@link com.demo.resortslite.BookingController}
 * to cache booking data in Amazon ElastiCache for Redis with a configurable TTL.</p>
 *
 * <h3>Why this fixes cr-java-0067</h3>
 * <ul>
 *   <li><strong>Bounded memory</strong>: Every cache entry is written with an explicit TTL
 *       (default 3600 s / 1 hour, configurable via {@code booking.cache.ttl-seconds}).
 *       Redis automatically evicts entries after the TTL expires, preventing indefinite
 *       memory growth and out-of-memory errors.</li>
 *   <li><strong>Cross-instance consistency</strong>: The cache is stored in the shared
 *       ElastiCache cluster, not in the local JVM heap.  All EC2 instances behind the
 *       AWS ALB read from and write to the same cache, eliminating stale data
 *       inconsistencies caused by instance-local caches.</li>
 *   <li><strong>Centralised management</strong>: Cache eviction, TTL policies, and
 *       memory limits are managed by ElastiCache, not by application code.</li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * The {@link RedisConnectionFactory} is provided by
 * {@link RedisSessionConfig#redisConnectionFactory()} and points at the ElastiCache
 * cluster configured via the {@code REDIS_HOST}, {@code REDIS_PORT}, and
 * {@code REDIS_PASSWORD} environment variables.
 *
 * <p>The TTL applied to each booking cache entry is controlled by the
 * {@code booking.cache.ttl-seconds} application property (default: 3600 s).
 * Override via the {@code BOOKING_CACHE_TTL_SECONDS} environment variable or
 * AWS SSM Parameter Store.</p>
 */
@Configuration
public class RedisCacheConfig {

    /**
     * Creates a {@link RedisTemplate}{@code <String, Object>} bean for booking cache
     * operations in Amazon ElastiCache for Redis.
     *
     * <ul>
     *   <li>Keys are serialised as plain UTF-8 strings (e.g. {@code "booking:BK-001"}).</li>
     *   <li>Values are serialised as JSON using Jackson, with type metadata embedded so
     *       that deserialisation reconstructs the correct Java type without unsafe casts.</li>
     * </ul>
     *
     * @param connectionFactory the Lettuce connection factory pointing at ElastiCache
     *                          (provided by {@link RedisSessionConfig#redisConnectionFactory()})
     * @return configured {@link RedisTemplate} for booking cache entries
     */
    @Bean(name = "bookingRedisTemplate")
    public RedisTemplate<String, Object> bookingRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key serialiser: plain UTF-8 string (e.g. "booking:BK-001")
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value serialiser: Jackson JSON with embedded type information so that
        // Object values can be deserialised back to their original Map/List types.
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
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
