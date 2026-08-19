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

/**
 * cz-java-0070 FIX: Redis configuration for the distributed booking cache.
 *
 * Replaces the local in-memory HashMap cache (BookingController source line 19) with a
 * distributed Redis-backed cache backed by an In-Cluster Redis StatefulSet deployed on
 * GKE Autopilot with GCP Persistent Disk-backed PVCs.
 *
 * Connection parameters are injected via environment variables:
 *   REDIS_HOST  — hostname/IP of the in-cluster Redis StatefulSet service (default: redis-service)
 *   REDIS_PORT  — Redis port (default: 6379)
 *
 * This configuration is compatible with the existing Spring Session Data Redis setup
 * (spring.session.store-type=redis) introduced by the cz-java-0069 fix.  Both the session
 * store and the booking cache share the same Redis instance but use distinct key namespaces
 * ("resortsLite:session:*" for sessions, "booking:cache:*" for the booking cache).
 */
@Configuration
public class RedisConfig {

    // cz-java-0070 FIX: Redis host injected via environment variable — no hardcoded hostname.
    // Points to the in-cluster Redis StatefulSet service on GKE Autopilot.
    @Value("${REDIS_HOST:redis-service}")
    private String redisHost;

    // cz-java-0070 FIX: Redis port injected via environment variable — no hardcoded port.
    @Value("${REDIS_PORT:6379}")
    private int redisPort;

    /**
     * cz-java-0070 FIX: LettuceConnectionFactory configured with env-var-backed host/port.
     * Lettuce is the default non-blocking Redis client bundled with spring-boot-starter-data-redis.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * cz-java-0070 FIX: Typed RedisTemplate<String, Object> for the distributed booking cache.
     * - Keys are serialized as plain UTF-8 strings (StringRedisSerializer).
     * - Values are serialized as JSON (GenericJackson2JsonRedisSerializer) so that booking
     *   event Maps survive serialization/deserialization across pod replicas without requiring
     *   Java-serialization compatibility.
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
