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
 * cz-java-0063: Spring Session Redis Configuration
 *
 * Replaces in-memory HttpSession with Spring Session backed by Amazon ElastiCache for Redis.
 * This enables stateless horizontal scaling on EKS — any pod can serve any request because
 * session data is stored externally in Redis rather than in JVM heap memory.
 *
 * cz-java-0070: RedisTemplate bean provides a distributed cache backed by Amazon ElastiCache,
 * replacing the former instance-local HashMap in BookingController. All EKS pod replicas share
 * the same cache entries, ensuring consistency during horizontal scaling.
 *
 * Redis connection is configured via environment variables injected through EKS ConfigMap/Secrets:
 *   REDIS_HOST  — ElastiCache primary endpoint (e.g., my-cluster.abc123.ng.0001.use1.cache.amazonaws.com)
 *   REDIS_PORT  — ElastiCache port (default: 6379)
 *
 * IRSA (IAM Roles for Service Accounts) is used for secure access to ElastiCache on EKS.
 */
@Configuration
@EnableRedisHttpSession // cz-java-0063: activates Spring Session; HttpSession calls are transparently routed to Redis
public class RedisSessionConfig {

    /**
     * cz-java-0063: Redis connection factory using environment variables for ElastiCache endpoint.
     * REDIS_HOST and REDIS_PORT are injected via EKS Pod environment (ConfigMap or Secrets Manager).
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * cz-java-0070 FIXED: RedisTemplate bean for distributed cache operations.
     * Replaces the instance-local HashMap<String, Object> bookingCache in BookingController.
     * Uses StringRedisSerializer for keys and GenericJackson2JsonRedisSerializer for values
     * so that booking objects are stored as JSON in Amazon ElastiCache and are readable by
     * all EKS pod replicas regardless of JVM instance.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
