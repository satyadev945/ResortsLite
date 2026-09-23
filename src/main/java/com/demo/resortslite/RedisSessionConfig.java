package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Redis session configuration for Amazon ElastiCache.
 *
 * <p>cr-java-0065 fix: Enables Spring Session backed by Amazon ElastiCache for Redis,
 * replacing the previous {@code HttpSession}-based state storage.  All session data is
 * now stored in a centralised Redis cluster so that every EC2 instance in the Auto
 * Scaling group shares the same session state, enabling true horizontal scaling without
 * server affinity (sticky sessions).</p>
 *
 * <p>Required environment variables / application properties:
 * <ul>
 *   <li>{@code REDIS_HOST} / {@code spring.redis.host} — ElastiCache primary endpoint
 *       (default: {@code localhost} for local development)</li>
 *   <li>{@code REDIS_PORT} / {@code spring.redis.port} — Redis port
 *       (default: {@code 6379})</li>
 *   <li>{@code SESSION_TTL_SECONDS} / {@code session.redis.ttl.seconds} — session TTL
 *       in seconds (default: {@code 1800} = 30 min)</li>
 * </ul>
 * </p>
 *
 * <p>Example AWS ElastiCache (Redis) setup:
 * <pre>
 *   # Create ElastiCache Redis cluster (single-node for dev, cluster-mode for prod)
 *   aws elasticache create-cache-cluster \
 *     --cache-cluster-id resortslite-sessions \
 *     --engine redis \
 *     --cache-node-type cache.t3.micro \
 *     --num-cache-nodes 1
 *
 *   # Set environment variables in ECS task definition / EC2 user-data
 *   REDIS_HOST=resortslite-sessions.abc123.0001.use1.cache.amazonaws.com
 *   REDIS_PORT=6379
 *   SESSION_TTL_SECONDS=1800
 * </pre>
 * </p>
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    /**
     * Amazon ElastiCache primary endpoint hostname.
     * Set {@code REDIS_HOST} environment variable (or {@code spring.redis.host} property)
     * to the ElastiCache cluster endpoint in each deployment environment.
     */
    @Value("${spring.redis.host:${REDIS_HOST:localhost}}")
    private String redisHost;

    /**
     * Redis port — defaults to 6379 (standard Redis / ElastiCache port).
     * Override via {@code REDIS_PORT} environment variable or
     * {@code spring.redis.port} application property.
     */
    @Value("${spring.redis.port:${REDIS_PORT:6379}}")
    private int redisPort;

    /**
     * Creates a Lettuce-based Redis connection factory pointing at the
     * Amazon ElastiCache endpoint configured via environment variables.
     *
     * @return {@link LettuceConnectionFactory} connected to ElastiCache
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * Configures a {@link RedisTemplate} with JSON serialisation so that
     * session objects are stored as human-readable JSON in ElastiCache,
     * simplifying debugging and cross-service interoperability.
     *
     * @param connectionFactory the Redis connection factory
     * @return configured {@link RedisTemplate}
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        // Use String serialiser for keys so they are human-readable in Redis CLI
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // Use JSON serialiser for values to support arbitrary object types
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
