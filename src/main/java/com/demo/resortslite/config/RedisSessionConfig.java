package com.demo.resortslite.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;

/**
 * Redis Session Configuration for Azure Cache for Redis
 * FIXED cr-java-0065: Externalized HTTP session state to Azure Cache for Redis
 * 
 * This configuration enables:
 * - Stateless application architecture
 * - Horizontal scaling across multiple instances
 * - Session persistence across instance restarts
 * - Load balancing without session affinity
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800) // 30 minutes session timeout
public class RedisSessionConfig {

    @Value("${spring.redis.host}")
    private String redisHost;

    @Value("${spring.redis.port}")
    private int redisPort;

    @Value("${spring.redis.password}")
    private String redisPassword;

    @Value("${spring.redis.ssl:true}")
    private boolean useSsl;

    /**
     * Configure Redis connection factory for Azure Cache for Redis
     * Uses Lettuce client with SSL support for secure connections
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(redisHost);
        redisConfig.setPort(redisPort);
        redisConfig.setPassword(redisPassword);

        LettuceClientConfiguration.LettuceClientConfigurationBuilder clientConfig = 
            LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2));

        if (useSsl) {
            clientConfig.useSsl();
        }

        return new LettuceConnectionFactory(redisConfig, clientConfig.build());
    }

    /**
     * Configure RedisTemplate for storing session data
     * Uses JSON serialization for complex objects
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Use String serializer for keys
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        
        // Use JSON serializer for values
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        
        template.afterPropertiesSet();
        return template;
    }
}
