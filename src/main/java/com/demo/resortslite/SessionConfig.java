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
 * cz-java-0063 [Server-side Sessions / HIGH] — Spring Session + Amazon ElastiCache (Redis)
 *
 * Replaces the default in-memory HttpSession with a distributed session store backed by
 * Amazon ElastiCache for Redis. The @EnableRedisHttpSession annotation registers a
 * SessionRepositoryFilter that transparently intercepts every HttpSession call in the
 * application (e.g. BookingController) and delegates storage to Redis.
 *
 * This ensures:
 *  - Session data survives container restarts on EKS.
 *  - All pod replicas share the same session state (horizontal scaling safe).
 *  - Session TTL is controlled by maxInactiveIntervalInSeconds (default 1800 s).
 *
 * cz-java-0070 [Local Caches / LOW] — RedisTemplate for distributed cache
 *
 * Provides a RedisTemplate<String, Object> bean used by BookingController to store
 * booking cache entries in Amazon ElastiCache (Redis) instead of an instance-local
 * HashMap. Cache entries are shared across all EKS pod replicas with a configurable TTL.
 *
 * Redis connection is configured via environment variables injected by a Kubernetes
 * Secret / ConfigMap, avoiding hardcoded credentials in source code:
 *   REDIS_HOST  — ElastiCache primary endpoint (e.g. my-cluster.abc123.ng.0001.use1.cache.amazonaws.com)
 *   REDIS_PORT  — Redis port (default 6379)
 *
 * IRSA (IAM Roles for Service Accounts) is used on EKS to grant the pod permission to
 * reach the ElastiCache cluster without embedding AWS credentials.
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class SessionConfig {

    /**
     * Builds a Lettuce-based Redis connection factory using environment variables
     * REDIS_HOST and REDIS_PORT so that no infrastructure details are hardcoded.
     *
     * @return RedisConnectionFactory backed by Amazon ElastiCache
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * cz-java-0070 [Local Caches / LOW]: Provides a RedisTemplate for distributed caching
     * in BookingController. Replaces the former instance-local HashMap (bookingCache) with
     * an Amazon ElastiCache (Redis) backed store shared across all EKS pod replicas.
     *
     * Uses StringRedisSerializer for keys and GenericJackson2JsonRedisSerializer for values
     * so that booking Map objects are serialised as JSON in Redis.
     *
     * @param redisConnectionFactory the Lettuce connection factory wired above
     * @return RedisTemplate<String, Object> for booking cache operations
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
