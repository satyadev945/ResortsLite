package com.demo.resortslite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

// cz-java-0069: @EnableRedisHttpSession activates Spring Session backed by Amazon ElastiCache
// (Redis). All HttpSession operations are transparently redirected to Redis, ensuring session
// state is shared across all EKS pod replicas and survives container restarts/scaling events.
@SpringBootApplication
@EnableRedisHttpSession
public class ResortsLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResortsLiteApplication.class, args);
    }
}
