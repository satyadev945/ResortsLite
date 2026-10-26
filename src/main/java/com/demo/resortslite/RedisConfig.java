package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * cz-java-0070: Redis Cache Configuration
 *
 * Provides a RedisTemplate<String, Object> bean used by BookingController to store
 * and retrieve booking entries in Amazon ElastiCache for Redis on EKS.
 *
 * Why this fixes cz-java-0070 (Local Caches):
 *   - The previous static HashMap<String, Object> bookingCache in BookingController
 *     was instance-local: each EKS pod replica maintained its own independent copy.
 *   - When the ALB routed a /status/{bookingId} request to a different pod than the
 *     one that created the booking, the cache lookup returned null — breaking the
 *     application under horizontal scaling.
 *   - RedisTemplate delegates all cache reads/writes to Amazon ElastiCache for Redis,
 *     a shared, distributed cache visible to every EKS pod replica simultaneously.
 *   - Cache entries are stored with a configurable TTL (BOOKING_CACHE_TTL_SECONDS)
 *     to prevent unbounded memory growth in the Redis cluster.
 *
 * EKS Deployment:
 *   - REDIS_HOST and REDIS_PORT are injected as environment variables in the EKS Pod
 *     spec (via ConfigMap or AWS Secrets Manager sidecar).
 *   - IRSA (IAM Roles for Service Accounts) is used for secure, credential-free access
 *     to Amazon ElastiCache for Redis without hardcoded credentials in source code.
 *   - BOOKING_CACHE_TTL_SECONDS controls cache entry TTL (default: 3600 seconds / 1 hour).
 *
 * Serialization:
 *   - Keys are serialized as plain UTF-8 strings (StringRedisSerializer).
 *   - Values are serialized as JSON (GenericJackson2JsonRedisSerializer) so that
 *     Map<String, Object> booking entries survive pod restarts and are human-readable
 *     in Redis CLI / ElastiCache monitoring tools.
 */
@Configuration
public class RedisConfig {

    /**
     * cz-java-0070: Configures RedisTemplate<String, Object> for booking cache operations.
     *
     * Uses:
     *   - StringRedisSerializer for keys  → readable key names like "booking:BK-XXXXXXXX"
     *   - GenericJackson2JsonRedisSerializer for values → JSON-serialized Map<String, Object>
     *
     * The RedisConnectionFactory is auto-configured by Spring Boot using:
     *   spring.redis.host  → ${REDIS_HOST:localhost}
     *   spring.redis.port  → ${REDIS_PORT:6379}
     *   spring.redis.password → ${REDIS_PASSWORD:}
     *   spring.redis.ssl   → ${REDIS_SSL:false}
     * All values are externalised via environment variables in application.properties.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        // Use String serializer for keys so cache keys are human-readable in Redis CLI
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // Use JSON serializer for values to support Map<String, Object> round-trips
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
