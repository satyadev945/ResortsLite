package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * cz-java-0070 fix: Redis configuration for Amazon ElastiCache (Redis) on EKS.
 * Provides a typed RedisTemplate<String, Object> bean used to replace the former
 * local in-memory HashMap cache in BookingController. Connection details (REDIS_HOST,
 * REDIS_PORT, REDIS_PASSWORD) are injected via Kubernetes Secrets / EKS Pod spec
 * environment variables, as configured in application.properties.
 */
@Configuration
public class RedisConfig {

    /**
     * Configures a RedisTemplate with String keys and JSON-serialized Object values,
     * enabling type-safe access to Amazon ElastiCache (Redis) from BookingController.
     *
     * @param connectionFactory auto-configured by Spring Boot using REDIS_HOST / REDIS_PORT
     *                          environment variables (spring.redis.host / spring.redis.port)
     * @return typed RedisTemplate for use as a distributed cache across EKS pod replicas
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
