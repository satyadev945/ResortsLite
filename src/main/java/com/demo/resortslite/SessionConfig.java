package com.demo.resortslite;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Spring Session Configuration for Redis-backed HTTP sessions
 * 
 * FIXED cz-java-0069: In-Memory Session Storage
 * 
 * This configuration enables Spring Session Data Redis, which replaces the default
 * in-memory HttpSession with a Redis-backed implementation. Session data is stored
 * in Google Cloud Memorystore for Redis, enabling:
 * 
 * 1. Horizontal Scaling: Multiple GKE pods can share session state
 * 2. High Availability: Sessions survive pod restarts and failures
 * 3. Load Balancing: Requests can be routed to any pod without session loss
 * 
 * Configuration:
 * - maxInactiveIntervalInSeconds: Session timeout (30 minutes)
 * - Redis connection details are configured in application.properties
 * - Credentials managed via GKE Workload Identity and Secret Manager
 * 
 * Environment Variables Required:
 * - REDIS_HOST: Redis server hostname (Google Cloud Memorystore endpoint)
 * - REDIS_PORT: Redis server port (default: 6379)
 * - REDIS_PASSWORD: Redis authentication password (optional, from Secret Manager)
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800) // 30 minutes session timeout
public class SessionConfig {
    // Spring Session automatically configures Redis connection using properties
    // from application.properties (spring.redis.host, spring.redis.port, etc.)
}
