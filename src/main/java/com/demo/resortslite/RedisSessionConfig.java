package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cr-java-0065 FIX: Spring Session configuration backed by Amazon ElastiCache for Redis.
 *
 * <p>Annotating this class with {@code @EnableRedisHttpSession} instructs Spring Session to
 * replace the default in-process {@code HttpSession} implementation with a Redis-backed
 * session store. All session attributes (e.g., "lastBooking", "guestName") are transparently
 * serialised and stored in the Amazon ElastiCache for Redis cluster rather than in local JVM
 * heap memory.</p>
 *
 * <p><strong>Cloud-readiness benefits:</strong></p>
 * <ul>
 *   <li>Eliminates server affinity — any EC2 instance behind the AWS ALB can serve any
 *       request because session state is held in the shared Redis cluster.</li>
 *   <li>Supports horizontal auto-scaling — new instances immediately have access to all
 *       active sessions without sticky-session routing.</li>
 *   <li>Survives instance termination — sessions are not lost when an EC2 instance is
 *       replaced during a rolling deployment or scale-in event.</li>
 *   <li>Configurable TTL — {@code maxInactiveIntervalInSeconds} controls session expiry
 *       centrally; Redis handles eviction automatically.</li>
 * </ul>
 *
 * <p><strong>cr-java-0067 FIX:</strong> Also declares a {@code RedisTemplate<String, Object>}
 * bean used by {@link BookingController} to cache booking entries in Amazon ElastiCache for
 * Redis with a configurable TTL. This replaces the previous unbounded static in-memory
 * {@code HashMap} that had no expiration policy, caused indefinite memory growth, and was
 * invisible to other EC2 instances in the cluster.</p>
 *
 * <p><strong>Required configuration (application.properties / environment variables):</strong></p>
 * <pre>
 *   spring.redis.host   = ${SPRING_REDIS_HOST}   # ElastiCache primary endpoint
 *   spring.redis.port   = ${SPRING_REDIS_PORT}   # default 6379
 *   spring.redis.ssl    = ${SPRING_REDIS_SSL}    # true for in-transit encryption
 *   spring.redis.password = ${SPRING_REDIS_PASSWORD}  # AUTH token if enabled
 *   app.cache.booking.ttl-seconds = ${APP_CACHE_BOOKING_TTL_SECONDS:3600}  # booking cache TTL
 * </pre>
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    // Spring Boot auto-configuration wires the LettuceConnectionFactory using the
    // spring.redis.* properties. No additional bean definitions are required for session.
    // To customise the connection factory (e.g., SSL, cluster mode, connection pool),
    // add a LettuceConnectionFactory @Bean override in this class.

    /**
     * cr-java-0067 FIX: Provides a typed {@code RedisTemplate<String, Object>} bean for
     * caching booking entries in Amazon ElastiCache for Redis with TTL-based expiration.
     *
     * <p>Uses {@link StringRedisSerializer} for keys (human-readable "booking:<id>" format)
     * and {@link GenericJackson2JsonRedisSerializer} for values (JSON serialization of
     * {@code Map<String, Object>} booking payloads). This ensures cache entries are
     * inspectable via Redis CLI and compatible with any JVM instance in the cluster.</p>
     *
     * @param connectionFactory the Lettuce connection factory auto-configured by Spring Boot
     *                          using the spring.redis.* properties
     * @return a configured {@code RedisTemplate<String, Object>} for booking cache operations
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        // Use String serializer for keys so cache entries are human-readable in Redis CLI
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // Use JSON serializer for values to support Map<String, Object> booking payloads
        // and ensure cross-instance deserialization compatibility
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
