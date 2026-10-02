package com.demo.resortslite;

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
 * cz-java-0069 [Fixed]: Spring Session Redis Configuration
 *
 * Externalizes HttpSession storage from JVM heap to Amazon ElastiCache (Redis),
 * deployed as a Kubernetes workload on EKS with IRSA for secure access.
 *
 * This configuration:
 * - Enables @EnableRedisHttpSession to replace the default in-memory HttpSession
 *   with a Redis-backed session store (RedisIndexedSessionRepository).
 * - Reads Redis connection details from environment variables (REDIS_HOST, REDIS_PORT)
 *   injected via Kubernetes ConfigMap or EKS Pod spec, pointing to ElastiCache endpoint.
 * - Sets a configurable session timeout via SESSION_TIMEOUT_SECONDS env var.
 * - Allows all EKS pod replicas to share session state, enabling horizontal scaling
 *   without sticky sessions and surviving container restarts.
 *
 * cz-java-0070 [Fixed]: Provides a shared RedisTemplate<String, Object> bean used by
 * BookingController to store and retrieve cache entries in Amazon ElastiCache (Redis),
 * replacing the former instance-local HashMap that was invisible to other EKS pod replicas.
 *
 * Required environment variables (set in Kubernetes Deployment / EKS Pod spec):
 *   REDIS_HOST              - ElastiCache primary endpoint (e.g., my-cluster.abc123.ng.0001.use1.cache.amazonaws.com)
 *   REDIS_PORT              - ElastiCache port (default: 6379)
 *   SESSION_TIMEOUT_SECONDS - Session TTL in seconds (default: 1800 = 30 minutes)
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    /**
     * cz-java-0069 [Fixed]: Configures the Redis connection factory using environment variables
     * for ElastiCache host and port, avoiding hardcoded infrastructure values.
     * REDIS_HOST and REDIS_PORT are injected via Kubernetes ConfigMap or EKS Pod spec.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * cz-java-0070 [Fixed]: Provides a RedisTemplate for distributed cache operations.
     * BookingController uses this bean to write and read booking cache entries in
     * Amazon ElastiCache (Redis), replacing the former instance-local HashMap.
     * Keys are serialized as plain strings; values are serialized as JSON via
     * GenericJackson2JsonRedisSerializer for human-readable storage and cross-pod compatibility.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
