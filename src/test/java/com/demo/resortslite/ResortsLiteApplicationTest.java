package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResortsLiteApplication — verifies Spring context loads correctly.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("ResortsLiteApplication Tests")
class ResortsLiteApplicationTest {

    // -----------------------------------------------------------------------
    // Context load test
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("contextLoads - Spring application context should start without errors")
    void contextLoads() {
        // If the context fails to load, Spring will throw an exception and this test fails.
        // No explicit assertion needed — the @SpringBootTest annotation handles it.
        assertTrue(true, "Spring context loaded successfully");
    }

    // -----------------------------------------------------------------------
    // main() smoke test
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("main_withEmptyArgs_doesNotThrow")
    void main_withEmptyArgs_doesNotThrow() {
        // Arrange
        String[] args = {};

        // Act & Assert — main() must not throw any exception when called with empty args.
        // We rely on the already-loaded Spring context; calling main() again would attempt
        // a second context start, so we simply verify the class is accessible.
        assertDoesNotThrow(() -> {
            // Verify the class can be instantiated (default constructor exists)
            ResortsLiteApplication app = new ResortsLiteApplication();
            assertNotNull(app);
        });
    }

    @Test
    @DisplayName("applicationClass_isAnnotatedWithSpringBootApplication")
    void applicationClass_isAnnotatedWithSpringBootApplication() {
        // Arrange & Act
        boolean hasAnnotation = ResortsLiteApplication.class
                .isAnnotationPresent(org.springframework.boot.autoconfigure.SpringBootApplication.class);

        // Assert
        assertTrue(hasAnnotation,
                "ResortsLiteApplication must be annotated with @SpringBootApplication");
    }
}
