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
 * cz-java-0070: Distributed Redis Cache Configuration — replaces local in-memory cache.
 *
 * PROBLEM (cz-java-0070): The original BookingController used a local in-memory cache
 * (static HashMap<String, Object> bookingCache) that does NOT work when containers scale
 * horizontally. Each AKS pod replica maintains its own isolated in-memory cache, causing
 * cache inconsistency, stale reads, and data loss on pod restarts or scale-out events.
 *
 * REMEDIATION (cz-java-0070): Deploy In-Cluster Redis with Helm and Azure Disk PVC on AKS.
 *
 * Deploy a Redis cluster inside AKS using the Bitnami Redis Helm chart backed by Azure
 * Managed Disk persistent volumes, providing distributed caching without an external
 * managed service dependency.
 *
 * Helm deployment command (run once per AKS cluster):
 *   helm repo add bitnami https://charts.bitnami.com/bitnami
 *   helm install redis bitnami/redis \
 *     --set architecture=standalone \
 *     --set auth.enabled=false \
 *     --set master.persistence.enabled=true \
 *     --set master.persistence.storageClass=managed-premium \
 *     --set master.persistence.size=8Gi \
 *     --namespace resortslite \
 *     --create-namespace
 *
 * After deployment, set the following environment variables in the AKS pod spec / ConfigMap:
 *   REDIS_HOST=redis-master.resortslite.svc.cluster.local
 *   REDIS_PORT=6379
 *
 * All AKS pod replicas connect to the same Redis instance, ensuring cache consistency
 * across horizontal scale-out events and container restarts.
 *
 * KEDA Redis Queue-Based Autoscaling: KEDA monitors Redis queue depth metrics
 * (via the Redis scaler) to dynamically scale AKS pods based on session/booking
 * load, preventing over-provisioning during traffic spikes.
 *
 * Connection parameters are sourced from environment variables:
 *   REDIS_HOST  - Redis server hostname (default: localhost)
 *   REDIS_PORT  - Redis server port     (default: 6379)
 */
@Configuration
public class RedisConfig {

    // cz-java-0070: Redis host sourced from environment variable REDIS_HOST.
    // In AKS, set this to the Redis service DNS name deployed via Bitnami Helm chart:
    //   redis-master.resortslite.svc.cluster.local
    // This ensures all pod replicas connect to the same distributed Redis instance,
    // replacing the local in-memory HashMap cache that broke horizontal scaling.
    @Value("${REDIS_HOST:localhost}")
    private String redisHost;

    // cz-java-0070: Redis port sourced from environment variable REDIS_PORT.
    // Default is 6379 (standard Redis port). Override via environment variable for
    // non-standard deployments or Azure Cache for Redis (which uses 6380 with TLS).
    @Value("${REDIS_PORT:6379}")
    private int redisPort;

    /**
     * cz-java-0070: Creates a Lettuce-based Redis connection factory using
     * environment-variable-driven host and port configuration.
     * Connects to the in-cluster Redis deployed via Bitnami Helm chart on AKS,
     * backed by Azure Managed Disk PVC for persistence.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * cz-java-0070: Configures a RedisTemplate with JSON serialization for
     * storing booking objects as JSON values in Redis. String keys are used
     * for human-readable cache key inspection (e.g., "booking:<bookingId>").
     *
     * This RedisTemplate is injected into BookingController to replace the
     * local static HashMap (bookingCache) that was identified as a local cache
     * violation (cz-java-0070) breaking horizontal scaling on AKS.
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
