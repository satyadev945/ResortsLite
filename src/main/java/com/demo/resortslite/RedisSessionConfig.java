package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

import java.time.Duration;

/**
 * cz-java-0063 [FIXED]: Redis-backed HTTP session configuration.
 *
 * Replaces the default in-memory HttpSession with Spring Session backed by
 * Azure Cache for Redis. Redis credentials are injected at runtime from
 * Azure Key Vault using the Secrets Store CSI Driver on AKS, ensuring that
 * no secrets are hard-coded and that all AKS pod replicas share the same
 * session store — enabling stateless horizontal scaling and zero-downtime
 * rolling deployments.
 *
 * cz-java-0070 [FIXED]: Also exposes a RedisTemplate<String, Object> bean used
 * by BookingController to store booking entries in the shared Azure Cache for
 * Redis with a configurable TTL, replacing the former local static HashMap cache
 * that was invisible to other AKS pod replicas.
 *
 * Required environment variables (supplied via AKS SecretProviderClass /
 * Kubernetes Secret mounted from Azure Key Vault CSI Driver):
 *   REDIS_HOST     – Azure Cache for Redis hostname
 *   REDIS_PORT     – Redis port (default 6380 for TLS)
 *   REDIS_PASSWORD – Redis access key from Key Vault
 *   REDIS_SSL      – "true" to enable TLS (recommended for Azure Cache)
 *   SESSION_TIMEOUT_SECONDS – session TTL in seconds (default 1800)
 *   BOOKING_CACHE_TTL_SECONDS – booking cache entry TTL in seconds (default 3600)
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    // cz-java-0063: Redis host injected from environment variable / Key Vault CSI secret
    @Value("${REDIS_HOST:localhost}")
    private String redisHost;

    // cz-java-0063: Redis port injected from environment variable / Key Vault CSI secret
    @Value("${REDIS_PORT:6380}")
    private int redisPort;

    // cz-java-0063: Redis password injected from environment variable / Key Vault CSI secret
    @Value("${REDIS_PASSWORD:}")
    private String redisPassword;

    // cz-java-0063: TLS flag — set to true for Azure Cache for Redis (port 6380)
    @Value("${REDIS_SSL:true}")
    private boolean redisSsl;

    /**
     * Lettuce connection factory wired to Azure Cache for Redis.
     * TLS is enabled by default to satisfy Azure Cache for Redis requirements.
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(redisHost);
        redisConfig.setPort(redisPort);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            redisConfig.setPassword(redisPassword);
        }

        LettuceClientConfiguration.LettuceClientConfigurationBuilder clientConfigBuilder =
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofSeconds(5));

        if (redisSsl) {
            clientConfigBuilder.useSsl();
        }

        return new LettuceConnectionFactory(redisConfig, clientConfigBuilder.build());
    }

    /**
     * cz-java-0070 [FIXED]: RedisTemplate bean for distributed cache operations.
     *
     * Used by BookingController to store and retrieve booking entries in Azure Cache
     * for Redis with a configurable TTL (BOOKING_CACHE_TTL_SECONDS). Keys are
     * serialised as plain strings; values are serialised as JSON via
     * GenericJackson2JsonRedisSerializer so that any AKS pod replica can deserialise
     * the cached booking Map without class-path coupling.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate() {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory());
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
