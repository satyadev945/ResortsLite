package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Tests for ResortsLiteApplication – verifies Spring context loads correctly.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResortsLiteApplicationTest {

    /**
     * Verifies that the Spring application context loads without errors.
     */
    @Test
    void contextLoads() {
        // If the context fails to load, this test will fail automatically
    }

    /**
     * Verifies that the main() entry-point can be invoked without throwing.
     * Uses the test profile to ensure H2 in-memory DB is used instead of Oracle.
     */
    @Test
    void main_doesNotThrow() {
        // Pass the test profile so Spring Boot uses H2 (application-test.properties)
        // instead of the production Oracle datasource (application.properties)
        assertDoesNotThrow(() ->
            ResortsLiteApplication.main(new String[]{"--spring.profiles.active=test",
                "--spring.main.web-application-type=none"}));
    }
}
