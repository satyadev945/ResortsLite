package com.demo.resortslite.config;

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
 * cz-java-0063: Externalizes HTTP session storage from in-memory (per-instance) to
 * Amazon ElastiCache (Redis) via Spring Session.  All container replicas share the
 * same Redis-backed session store, so sessions survive restarts and horizontal scaling.
 *
 * cz-java-0070: Also exposes a RedisTemplate<String, Object> bean used by BookingController
 * to store booking cache entries in Amazon ElastiCache (Redis), replacing the former
 * instance-local HashMap cache that was invisible to other EKS container replicas.
 *
 * Connection parameters are injected from environment variables at runtime:
 *   REDIS_HOST     — ElastiCache primary endpoint (default: localhost)
 *   REDIS_PORT     — Redis port                   (default: 6379)
 *   REDIS_PASSWORD — Auth token / password        (default: empty)
 */
@Configuration
@EnableRedisHttpSession
public class RedisSessionConfig {

    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.setPassword(redisPassword);
        }
        return new LettuceConnectionFactory(config);
    }

    /**
     * cz-java-0070: RedisTemplate bean for distributed cache operations.
     * Uses StringRedisSerializer for keys and GenericJackson2JsonRedisSerializer for values
     * so that booking objects are stored as JSON in ElastiCache and are readable by all
     * container replicas regardless of JVM instance.
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
