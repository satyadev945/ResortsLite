package com.demo.resortslite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * ResortsLiteApplication
 *
 * cz-java-0069 FIX: @EnableRedisHttpSession activates Spring Session with Redis as the
 * backing store. This replaces the default in-memory HttpSession with a distributed
 * session store (Azure Cache for Redis on AKS), ensuring session data survives container
 * restarts and is shared across all pod replicas.
 *
 * Redis connection details are injected at runtime from Azure Key Vault via the
 * Secrets Store CSI Driver with Workload Identity:
 *   REDIS_HOST     -> Azure Cache for Redis hostname
 *   REDIS_PORT     -> TLS port (default 6380)
 *   REDIS_PASSWORD -> Primary access key (mounted as Kubernetes secret from Key Vault)
 */
@SpringBootApplication
@EnableRedisHttpSession
public class ResortsLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResortsLiteApplication.class, args);
    }
}
