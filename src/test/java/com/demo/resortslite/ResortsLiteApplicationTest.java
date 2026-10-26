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
     */
    @Test
    void main_doesNotThrow() {
        assertDoesNotThrow(() -> ResortsLiteApplication.main(new String[]{}));
    }
}
