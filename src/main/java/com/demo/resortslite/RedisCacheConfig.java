package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * cr-java-0067 fix: Amazon ElastiCache for Redis — booking cache configuration.
 *
 * <p>Provides a {@link RedisTemplate} bean used by {@link BookingController} to replace
 * the former unbounded static {@code HashMap<String, Object> bookingCache} with a
 * centralised, TTL-controlled cache backed by Amazon ElastiCache for Redis.</p>
 *
 * <p>Key improvements over the in-memory HashMap:
 * <ul>
 *   <li><b>TTL enforcement</b>: every cache entry is written with an explicit expiration
 *       (default 3600 s / 1 hour, configurable via {@code BOOKING_CACHE_TTL_SECONDS}),
 *       preventing indefinite memory growth and stale data.</li>
 *   <li><b>Cross-instance consistency</b>: all application instances in the auto-scaling
 *       group share the same ElastiCache cluster, so a cache entry written by instance A
 *       is immediately visible to instances B, C, … — eliminating the instance-local
 *       cache inconsistency that caused stale data in multi-node deployments.</li>
 *   <li><b>Cloud-native management</b>: ElastiCache is monitored via AWS CloudWatch,
 *       supports automatic failover (Multi-AZ), and provides in-transit encryption
 *       (TLS) and at-rest encryption for compliance requirements.</li>
 * </ul>
 * </p>
 *
 * <p>The {@link RedisConnectionFactory} is provided by {@link RedisSessionConfig}, which
 * is already configured to connect to the ElastiCache primary endpoint supplied via the
 * {@code REDIS_HOST} / {@code REDIS_PORT} environment variables.</p>
 */
@Configuration
public class RedisCacheConfig {

    /**
     * Configures a {@link RedisTemplate} for storing and retrieving booking cache entries
     * in Amazon ElastiCache for Redis.
     *
     * <ul>
     *   <li>Keys are serialised as plain UTF-8 strings (e.g. {@code booking:cache:BK-XXXXXXXX}).</li>
     *   <li>Values are serialised as JSON using {@link GenericJackson2JsonRedisSerializer},
     *       which preserves the {@code Map<String, Object>} structure and supports
     *       deserialisation without requiring a concrete target class.</li>
     * </ul>
     *
     * @param connectionFactory the Redis connection factory provided by {@link RedisSessionConfig}
     * @return a fully configured {@link RedisTemplate} instance
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serialiser for keys so cache entries are human-readable in Redis CLI
        // and AWS ElastiCache monitoring dashboards.
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Use JSON serialiser for values to preserve Map<String, Object> structure across
        // serialisation/deserialisation cycles without requiring a concrete target class.
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
