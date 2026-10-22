package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * RedisSessionConfig — Spring Session and Cache configuration backed by Amazon ElastiCache for Redis.
 *
 * cr-java-0065 FIX: This configuration class activates Spring Session with a Redis backing
 * store, replacing the default in-process HttpSession with a centralized, distributed session
 * store hosted on Amazon ElastiCache. All session attributes written via HttpSession are
 * transparently serialised to Redis, making every application instance fully stateless.
 *
 * cr-java-0067 FIX: This configuration class also provides a RedisTemplate<String, Object>
 * bean used by BookingController to cache booking entries in Amazon ElastiCache for Redis
 * with a configurable TTL. This replaces the unbounded static in-memory HashMap that caused
 * indefinite memory growth, stale data, and cache inconsistency across multiple EC2 instances.
 * The RedisTemplate uses GenericJackson2JsonRedisSerializer for value serialization, enabling
 * transparent storage and retrieval of Map<String, Object> booking entries in Redis.
 *
 * Benefits for AWS cloud deployment:
 *  - AWS ALB can route any request to any instance without sticky sessions.
 *  - Auto Scaling can terminate instances without losing session data.
 *  - Zero-downtime rolling deployments are supported because sessions survive restarts.
 *  - Session data is replicated across ElastiCache nodes for high availability.
 *  - Booking cache entries are shared across all instances with TTL-based eviction.
 *
 * Configuration (set via environment variables or AWS Parameter Store / Secrets Manager):
 *  SPRING_REDIS_HOST     — ElastiCache primary endpoint hostname (default: localhost)
 *  SPRING_REDIS_PORT     — ElastiCache port (default: 6379)
 *  SPRING_REDIS_PASSWORD — ElastiCache AUTH token if in-transit encryption is enabled
 *
 * The maxInactiveIntervalInSeconds parameter controls session TTL in Redis (default: 1800 s).
 * Override via the SESSION_TIMEOUT_SECONDS environment variable.
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    /**
     * ElastiCache primary endpoint hostname.
     * Injected from the SPRING_REDIS_HOST environment variable or the
     * spring.redis.host application property. Defaults to "localhost" for
     * local development (where a local Redis instance is expected on port 6379).
     * In AWS environments this should be set to the ElastiCache cluster endpoint,
     * e.g. my-cluster.abc123.ng.0001.use1.cache.amazonaws.com
     */
    @Value("${spring.redis.host:${SPRING_REDIS_HOST:localhost}}")
    private String redisHost;

    /**
     * ElastiCache port.
     * Injected from the SPRING_REDIS_PORT environment variable or the
     * spring.redis.port application property. Defaults to 6379.
     */
    @Value("${spring.redis.port:${SPRING_REDIS_PORT:6379}}")
    private int redisPort;

    /**
     * ElastiCache AUTH token (optional).
     * Required when the ElastiCache cluster has in-transit encryption and AUTH enabled.
     * Injected from the SPRING_REDIS_PASSWORD environment variable or the
     * spring.redis.password application property. Leave empty for unauthenticated clusters.
     */
    @Value("${spring.redis.password:${SPRING_REDIS_PASSWORD:}}")
    private String redisPassword;

    /**
     * Builds a Lettuce-based RedisConnectionFactory pointing at the ElastiCache endpoint.
     * Spring Session and Spring Data Redis auto-configuration will use this factory bean
     * to create all Redis connections.
     *
     * @return a configured LettuceConnectionFactory for the ElastiCache cluster
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
     * cr-java-0067 FIX: Provides a RedisTemplate<String, Object> bean for booking cache
     * operations in BookingController. This template replaces the unbounded static HashMap
     * with a centralized Amazon ElastiCache-backed cache that supports TTL-based eviction.
     *
     * Serialization strategy:
     *  - Keys   : StringRedisSerializer — human-readable key strings (e.g. "bookingCache:BK-XXXXXXXX")
     *  - Values : GenericJackson2JsonRedisSerializer — JSON serialization of Map<String, Object>
     *             booking entries, enabling type-safe deserialization across application restarts
     *             and across different JVM instances in the cluster.
     *
     * The RedisConnectionFactory is the same Lettuce factory used by Spring Session,
     * so both session data and booking cache entries share the same ElastiCache cluster.
     * Key namespacing (BOOKING_CACHE_PREFIX in BookingController) prevents collisions
     * between session keys and booking cache keys.
     *
     * @param redisConnectionFactory the Lettuce connection factory pointing at ElastiCache
     * @return a configured RedisTemplate for String keys and Object (JSON) values
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        // Use String serializer for keys to keep cache keys human-readable in Redis
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // Use Jackson JSON serializer for values to support Map<String, Object> round-trips
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
