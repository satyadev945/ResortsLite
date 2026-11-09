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
 * cz-java-0069: Redis Session Configuration
 *
 * Externalizes HTTP session storage from in-memory (JVM heap) to Amazon ElastiCache (Redis)
 * deployed on EKS. This ensures session data survives container restarts and is shared
 * across all horizontally-scaled pod replicas — eliminating the in-memory session loss
 * blocker for containerized deployments.
 *
 * cz-java-0070: Also provides a RedisTemplate bean for distributed cache operations,
 * replacing the local in-memory HashMap cache in BookingController with Amazon ElastiCache (Redis).
 *
 * Redis connection parameters are injected via environment variables (REDIS_HOST, REDIS_PORT)
 * at runtime on EKS using IRSA for secure access to Amazon ElastiCache.
 */
@Configuration
@EnableRedisHttpSession // cz-java-0069: Enables Spring Session backed by Amazon ElastiCache (Redis) — replaces in-memory HttpSession
public class RedisSessionConfig {

    // cz-java-0069: Redis host injected via environment variable — points to Amazon ElastiCache endpoint on EKS
    @Value("${REDIS_HOST:localhost}")
    private String redisHost;

    // cz-java-0069: Redis port injected via environment variable — default 6379 for ElastiCache
    @Value("${REDIS_PORT:6379}")
    private int redisPort;

    /**
     * cz-java-0069: Configures Lettuce connection factory pointing to Amazon ElastiCache (Redis).
     * REDIS_HOST and REDIS_PORT are supplied as Kubernetes environment variables or EKS pod spec,
     * ensuring no hardcoded infrastructure values in the container image.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * cz-java-0070: RedisTemplate bean for distributed cache operations.
     * Replaces the local in-memory HashMap cache (bookingCache) in BookingController
     * with a shared Amazon ElastiCache (Redis) store accessible by all EKS pod replicas.
     * Uses StringRedisSerializer for keys and GenericJackson2JsonRedisSerializer for values
     * to ensure correct serialization of Map<String, Object> booking entries.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) { // cz-java-0070
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
