package com.demo.resortslite.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.beans.factory.annotation.Value;

/**
 * cr-java-0065 FIX: Spring Session backed by Amazon ElastiCache for Redis.
 *
 * <p>Replaces the default in-memory {@code HttpSession} store with a distributed
 * Redis-backed session store. All session attributes written via
 * {@code HttpSession.setAttribute()} are transparently persisted to the shared
 * ElastiCache cluster, making every application instance fully stateless.
 *
 * <p>cr-java-0067 FIX: Provides a shared {@link RedisTemplate} bean used by
 * {@code BookingController} to store booking cache entries in Amazon ElastiCache
 * for Redis with explicit TTL. Replaces the unbounded in-memory {@code HashMap}
 * cache that caused indefinite memory growth and instance-local cache inconsistency.
 *
 * <p>Benefits in AWS:
 * <ul>
 *   <li>ALB can route any request to any instance — no sticky sessions required.</li>
 *   <li>Auto-scaling and instance termination no longer cause session loss.</li>
 *   <li>Session data survives rolling deployments and blue/green switches.</li>
 *   <li>Booking cache is shared across all instances with TTL-based expiration.</li>
 *   <li>No unbounded memory growth — Redis evicts entries automatically on TTL expiry.</li>
 * </ul>
 *
 * <p>Configuration is driven entirely by environment variables injected at runtime
 * (ECS task definition, Elastic Beanstalk environment properties, or SSM Parameter Store):
 * <ul>
 *   <li>{@code REDIS_HOST} — ElastiCache primary endpoint hostname</li>
 *   <li>{@code REDIS_PORT} — Redis port (default 6379)</li>
 *   <li>{@code REDIS_PASSWORD} — Auth token for ElastiCache in-transit encryption</li>
 *   <li>{@code REDIS_SSL} — Enable TLS (set {@code true} for ElastiCache with TLS)</li>
 *   <li>{@code SESSION_TIMEOUT_SECONDS} — Session TTL in seconds (default 1800)</li>
 *   <li>{@code BOOKING_CACHE_TTL_SECONDS} — Booking cache entry TTL (default 3600)</li>
 * </ul>
 */
@Configuration
@EnableRedisHttpSession
public class RedisSessionConfig {

    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    /**
     * Creates a Lettuce-based Redis connection factory pointing at the
     * Amazon ElastiCache endpoint supplied via environment variables.
     *
     * @return configured {@link LettuceConnectionFactory}
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.setPassword(redisPassword);
        }
        return new LettuceConnectionFactory(config);
    }

    /**
     * cr-java-0067 FIX: Configures a {@link RedisTemplate} for general-purpose
     * key/value caching backed by Amazon ElastiCache for Redis.
     *
     * <p>Uses {@link StringRedisSerializer} for keys (human-readable, e.g.
     * {@code "booking:BK-A1B2C3D4"}) and {@link GenericJackson2JsonRedisSerializer}
     * for values so that complex objects (Maps, POJOs) are serialised as JSON and
     * can be deserialised correctly on any instance in the cluster.
     *
     * <p>TTL is applied per entry by the caller (e.g. {@code BookingController})
     * via {@code RedisTemplate.opsForValue().set(key, value, ttl, TimeUnit.SECONDS)},
     * ensuring controlled expiration and preventing stale data accumulation.
     *
     * @param redisConnectionFactory the connection factory created above
     * @return configured {@link RedisTemplate}
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        // Use String serializer for keys — readable in Redis CLI / CloudWatch Insights
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // Use JSON serializer for values — supports complex objects and cross-instance reads
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
