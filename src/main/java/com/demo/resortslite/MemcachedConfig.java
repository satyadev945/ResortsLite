package com.demo.resortslite;

import net.spy.memcached.AddrUtil;
import net.spy.memcached.MemcachedClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * MemcachedConfig — Spring configuration for Amazon ElastiCache Memcached client.
 *
 * Rule cz-java-0070 (Local Caches) fix:
 *   Provides a MemcachedClient bean backed by Amazon ElastiCache for Memcached.
 *   The cluster endpoint is resolved from the MEMCACHED_ENDPOINT environment variable,
 *   which is injected into the ECS Fargate task definition from AWS SSM Parameter Store
 *   at deploy time (e.g. /resortsLite/prod/memcached/endpoint).
 *
 *   This replaces the former instance-local HashMap cache in BookingController,
 *   making the distributed cache visible and consistent across all horizontally-scaled
 *   ECS Fargate container instances.
 *
 * Environment variables (set in ECS Fargate task definition via SSM Parameter Store):
 *   MEMCACHED_ENDPOINT  — ElastiCache cluster config endpoint, e.g.:
 *                         "my-cluster.cfg.use1.cache.amazonaws.com:11211"
 *                         SSM path: /resortsLite/prod/memcached/endpoint
 *
 * If MEMCACHED_ENDPOINT is not set (e.g. local development), the bean is not created
 * and BookingController gracefully skips cache operations (required = false).
 */
@Configuration
public class MemcachedConfig {

    private static final Logger log = Logger.getLogger(MemcachedConfig.class.getName());

    /**
     * Creates a MemcachedClient connected to the Amazon ElastiCache Memcached cluster.
     *
     * The MEMCACHED_ENDPOINT env var is populated from AWS SSM Parameter Store by the
     * ECS Fargate task definition's "secrets" or "environment" block, e.g.:
     *
     *   {
     *     "name": "MEMCACHED_ENDPOINT",
     *     "valueFrom": "arn:aws:ssm:us-east-1:123456789012:parameter/resortsLite/prod/memcached/endpoint"
     *   }
     *
     * @return MemcachedClient connected to ElastiCache, or null if endpoint is not configured.
     */
    @Bean
    public MemcachedClient memcachedClient() {
        // cz-java-0070: Read ElastiCache Memcached endpoint from environment variable.
        // Populated from AWS SSM Parameter Store via ECS Fargate task definition.
        String memcachedEndpoint = System.getenv("MEMCACHED_ENDPOINT");

        if (memcachedEndpoint == null || memcachedEndpoint.trim().isEmpty()) {
            log.warning("MEMCACHED_ENDPOINT environment variable is not set. " +
                    "Distributed cache (ElastiCache Memcached) will be unavailable. " +
                    "Set this variable in the ECS Fargate task definition from SSM Parameter Store " +
                    "path: /resortsLite/prod/memcached/endpoint");
            return null;
        }

        try {
            log.info("Connecting to Amazon ElastiCache Memcached at: " + memcachedEndpoint);
            return new MemcachedClient(AddrUtil.getAddresses(memcachedEndpoint));
        } catch (IOException e) {
            log.severe("Failed to connect to Amazon ElastiCache Memcached endpoint [" +
                    memcachedEndpoint + "]: " + e.getMessage());
            return null;
        }
    }
}
