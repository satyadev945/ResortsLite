package com.demo.resortslite;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cr-java-0065 FIX: Redis-backed HTTP Session Configuration
 *
 * <p>Activates Spring Session with Google Cloud Memorystore for Redis as the session store.
 * {@code @EnableRedisHttpSession} replaces the default in-memory {@code HttpSession}
 * implementation with a Redis-backed one, so all {@code session.setAttribute} /
 * {@code session.getAttribute} calls in {@link BookingController} are transparently
 * serialised to and deserialised from Redis.</p>
 *
 * <p><strong>Cloud-native benefits:</strong></p>
 * <ul>
 *   <li>Session data is stored centrally in Memorystore for Redis — every application
 *       instance in the cluster shares the same session store.</li>
 *   <li>Requests can be routed to any instance without server affinity (sticky sessions
 *       are no longer required on the load balancer).</li>
 *   <li>Sessions survive instance restarts, auto-scaling events, and rolling deployments.</li>
 *   <li>Session TTL is enforced by Redis, preventing unbounded memory growth.</li>
 * </ul>
 *
 * <p><strong>Runtime configuration (application.properties / environment variables):</strong></p>
 * <pre>
 *   spring.session.store-type=redis
 *   spring.redis.host=${REDIS_HOST:localhost}   # Memorystore for Redis IP
 *   spring.redis.port=${REDIS_PORT:6379}
 *   spring.session.timeout=${SESSION_TIMEOUT:1800}
 *   spring.session.redis.namespace=resorts:session
 * </pre>
 *
 * <p>The {@code maxInactiveIntervalInSeconds} parameter below matches the
 * {@code SESSION_TIMEOUT} environment variable default (1800 s = 30 min).</p>
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {
    // No additional beans required — Spring Boot auto-configures the Lettuce Redis
    // client from spring.redis.* properties and Spring Session wires everything together.
}
