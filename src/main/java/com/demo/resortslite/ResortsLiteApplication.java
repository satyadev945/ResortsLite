package com.demo.resortslite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

// cz-java-0063: Enable Spring Session backed by Redis (Amazon ElastiCache) so that
// HttpSession data is stored externally, allowing stateless EKS pods to scale horizontally.
@EnableRedisHttpSession
@SpringBootApplication
public class ResortsLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResortsLiteApplication.class, args);
    }
}
