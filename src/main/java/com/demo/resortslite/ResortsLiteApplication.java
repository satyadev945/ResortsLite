package com.demo.resortslite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

// FIX cz-java-0069: @EnableRedisHttpSession activates Spring Session backed by
// Amazon ElastiCache for Redis. All HTTP sessions are now stored externally in Redis,
// enabling stateless horizontal scaling on EKS and surviving container restarts.
// REDIS_HOST, REDIS_PORT, and REDIS_PASSWORD must be injected via Kubernetes ConfigMap/Secrets.
@SpringBootApplication
@EnableRedisHttpSession
public class ResortsLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResortsLiteApplication.class, args);
    }
}
