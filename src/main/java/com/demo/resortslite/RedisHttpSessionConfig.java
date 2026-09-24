package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cz-java-0063 [Fixed]: Spring Session configuration that replaces the default
 * in-memory HttpSession with a Redis-backed session store (Azure Cache for Redis).
 *
 * <p>With @EnableRedisHttpSession, every call to HttpSession.setAttribute() /
 * getAttribute() is transparently delegated to Azure Cache for Redis instead of
 * the local JVM heap.  This means:
 * <ul>
 *   <li>Session data survives pod restarts and container re-scheduling on AKS.</li>
 *   <li>All horizontal replicas share the same session store — no sticky sessions
 *       or session-replication overhead required.</li>
 *   <li>Redis credentials are injected at runtime via the Secrets Store CSI Driver
 *       from Azure Key Vault (see application.properties / environment variables).</li>
 * </ul>
 *
 * <p>maxInactiveIntervalInSeconds defaults to 1800 (30 min); override with
 * {@code spring.session.timeout} in application.properties if needed.
 *
 * <p>cz-java-0070 [Fixed]: Also exposes a {@link RedisTemplate} bean used by
 * {@link BookingController} to store booking cache entries in Azure Cache for Redis
 * instead of a local in-process HashMap. Cache entries are written with a TTL so
 * stale data is automatically evicted, and all AKS pod replicas share the same cache.
 */
@Configuration
@EnableRedisHttpSession
public class RedisHttpSessionConfig {

    /**
     * cz-java-0070 [Fixed]: RedisTemplate bean for general-purpose Redis operations
     * (e.g., booking cache in BookingController). Uses String keys and JSON-serialized
     * values so that cached objects survive across pod restarts and are readable by
     * any AKS replica. The underlying RedisConnectionFactory is auto-configured by
     * Spring Boot from the REDIS_HOST / REDIS_PORT / REDIS_PASSWORD environment
     * variables injected via the Azure Key Vault CSI Driver.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        // Use String serializer for keys so they are human-readable in Redis
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // Use JSON serializer for values to support arbitrary Object types
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
