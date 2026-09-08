package com.demo.resortslite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main entry point for the ResortsLite Spring Boot application.
 *
 * <p>Upgraded from Java 1.8 / Spring Boot 2.7.x to Java 17 / Spring Boot 3.2.x.
 * All javax.* imports have been migrated to jakarta.* as required by Jakarta EE 10.</p>
 */
@SpringBootApplication
public class ResortsLiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResortsLiteApplication.class, args);
    }
}
