package com.demo.resortslite;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * cz-java-0070: Redis configuration for Amazon ElastiCache (Redis) on EKS.
 *
 * Provides a RedisTemplate<String, Object> bean used by BookingController to store
 * booking cache entries in the shared ElastiCache cluster instead of the former
 * instance-local static HashMap. This ensures cache consistency across all EKS pod
 * replicas when the application scales horizontally.
 *
 * Connection details (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD, REDIS_SSL) are injected
 * via Kubernetes ConfigMaps and Secrets with IRSA-secured access, as configured in
 * application.properties.
 */
@Configuration
public class RedisConfig {

    /**
     * cz-java-0070: Configures a RedisTemplate with String keys and JSON-serialized Object
     * values, suitable for storing booking cache entries in Amazon ElastiCache (Redis).
     *
     * @param connectionFactory auto-configured by Spring Boot using REDIS_HOST / REDIS_PORT
     *                          environment variables defined in application.properties
     * @return RedisTemplate<String, Object> bean for cache operations
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serializer for keys (e.g. "booking:<bookingId>")
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Use Jackson JSON serializer for values so booking Map objects are stored as JSON
        Jackson2JsonRedisSerializer<Object> jsonSerializer = new Jackson2JsonRedisSerializer<>(Object.class);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL
        );
        jsonSerializer.setObjectMapper(objectMapper);

        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();

        return template;
    }
}
