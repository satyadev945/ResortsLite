package com.demo.resortslite;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * cz-java-0070: Redis configuration for Amazon ElastiCache integration.
 *
 * Configures a RedisTemplate<String, Object> bean with JSON serialization,
 * enabling the BookingController to store and retrieve booking cache entries
 * in the shared Amazon ElastiCache (Redis) cluster. Connection details
 * (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD, REDIS_SSL) are injected via
 * Kubernetes ConfigMaps and Secrets with IRSA-secured access on EKS,
 * as defined in application.properties.
 */
@Configuration
public class RedisConfig {

    /**
     * Provides a RedisTemplate configured with:
     * - StringRedisSerializer for keys (human-readable key names in Redis)
     * - GenericJackson2JsonRedisSerializer for values (JSON serialization of
     *   Map<String, Object> booking entries, compatible with horizontal scaling)
     *
     * @param connectionFactory auto-configured by spring-boot-starter-data-redis
     *                          using REDIS_HOST / REDIS_PORT / REDIS_PASSWORD env vars
     * @return configured RedisTemplate for distributed booking cache operations
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serializer for keys so cache entries are human-readable in Redis
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Use Jackson JSON serializer for values to support Map<String, Object> storage
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(new ObjectMapper());
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
