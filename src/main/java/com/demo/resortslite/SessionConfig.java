package com.demo.resortslite;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.ConfigureRedisAction;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Externalizes HttpSession state and the booking cache to Redis (Amazon ElastiCache),
 * so that every pod behind the load balancer sees the same sessions and cache entries.
 * The Redis endpoint comes from REDIS_HOST, REDIS_PORT, REDIS_PASSWORD and REDIS_SSL.
 */
@Configuration
@EnableCaching
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class SessionConfig {

    /** ElastiCache does not allow the CONFIG command Spring Session would run at start-up. */
    @Bean
    public static ConfigureRedisAction configureRedisAction() {
        return ConfigureRedisAction.NO_OP;
    }
}
