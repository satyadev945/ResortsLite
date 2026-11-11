package com.demo.resortslite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

// cz-java-0069: HttpSession externalised to Azure Cache for Redis (Spring Session);
// Redis credentials injected via Azure Key Vault CSI Driver (Workload Identity) as env vars.
@SpringBootApplication
@EnableRedisHttpSession(redisNamespace = "resortslite:session", maxInactiveIntervalInSeconds = 1800)
public class ResortsLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResortsLiteApplication.class, args);
    }
}
