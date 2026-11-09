package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cz-java-0063: Redis configuration for Spring Session backed by Amazon ElastiCache.
 *
 * <p>Enables externalized HTTP session storage so that all EKS pod replicas share the
 * same session state. Session data survives container restarts and is visible across
 * all instances behind an AWS ALB, enabling true horizontal scaling.
 *
 * <p>Connection parameters (REDIS_HOST, REDIS_PORT, REDIS_PASSWORD, REDIS_SSL) are
 * injected via Kubernetes Secrets / environment variables — see application.properties.
 * In production, use EKS IRSA with IAM authentication for secure, credential-free access
 * to Amazon ElastiCache for Redis.
 */
@Configuration
@EnableRedisHttpSession
public class RedisConfig {

    /**
     * cz-java-0063: Configures a RedisTemplate with JSON serialization so that session
     * attributes (e.g. booking maps, guest names) are stored as human-readable JSON in
     * Amazon ElastiCache for Redis rather than opaque Java-serialized bytes.
     *
     * @param connectionFactory auto-configured by Spring Boot from spring.redis.* properties
     * @return a RedisTemplate wired with String keys and JSON-serialized values
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        // Use String serializer for keys so they are human-readable in ElastiCache
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // Use JSON serializer for values to avoid Java serialization compatibility issues
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
