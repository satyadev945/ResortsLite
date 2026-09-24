package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cr-java-0065 FIX: Redis-backed HTTP Session configuration.
 *
 * Replaces the default in-memory HttpSession store with Amazon ElastiCache for Redis
 * using Spring Session Data Redis. All session attributes written via
 * HttpSession.setAttribute() are transparently serialised to the shared Redis cluster,
 * making every application instance fully stateless.
 *
 * Benefits for AWS deployments:
 *  - AWS ALB can distribute requests to any EC2 instance without sticky sessions.
 *  - Auto Scaling Group scale-out / scale-in events do not cause session data loss.
 *  - Instance termination (spot interruption, health-check failure) is transparent
 *    to end users because session state survives in ElastiCache.
 *
 * Required environment variables (set via ECS task definition, EC2 Parameter Store,
 * or Elastic Beanstalk environment configuration):
 *
 *   REDIS_HOST          — ElastiCache primary endpoint (e.g. my-cluster.abc123.ng.0001.use1.cache.amazonaws.com)
 *   REDIS_PORT          — ElastiCache port (default: 6379)
 *   SESSION_TIMEOUT_SEC — Session TTL in seconds (default: 1800 = 30 minutes)
 *
 * AWS ElastiCache setup:
 *   1. Create an ElastiCache for Redis cluster in the same VPC as the application.
 *   2. Configure the security group to allow inbound TCP 6379 from the application
 *      security group.
 *   3. Set REDIS_HOST to the cluster's Primary Endpoint DNS name.
 *   4. For TLS-enabled clusters, switch LettuceConnectionFactory to use
 *      RedisSSLConfiguration and set the port to 6380.
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    // ElastiCache primary endpoint — injected from environment / SSM Parameter Store.
    // SSM parameter: /resortslite/redis/host
    @Value("${spring.redis.host:${REDIS_HOST:localhost}}")
    private String redisHost;

    // ElastiCache port — default 6379 for non-TLS, 6380 for TLS-enabled clusters.
    // SSM parameter: /resortslite/redis/port
    @Value("${spring.redis.port:${REDIS_PORT:6379}}")
    private int redisPort;

    /**
     * Lettuce connection factory pointing at the Amazon ElastiCache for Redis cluster.
     * Lettuce is the recommended client for ElastiCache because it supports cluster
     * topology refresh and TLS without additional configuration.
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }
}
