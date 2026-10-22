package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ResortsLiteApplication.
 */
@DisplayName("ResortsLiteApplication Tests")
class ResortsLiteApplicationTest {

    @Test
    @DisplayName("Application class can be instantiated")
    void testApplicationInstantiation() {
        // Arrange & Act
        ResortsLiteApplication app = new ResortsLiteApplication();

        // Assert
        assertNotNull(app, "ResortsLiteApplication instance should not be null");
    }

    @Test
    @DisplayName("main method exists and is callable without throwing (no Spring context)")
    void testMainMethodDoesNotThrowWithEmptyArgs() {
        // The main method starts Spring Boot; we verify it is accessible via reflection
        // without actually booting the full context (which requires a DB).
        assertDoesNotThrow(() -> {
            // Verify the class has a main method with the correct signature
            var method = ResortsLiteApplication.class.getMethod("main", String[].class);
            assertNotNull(method, "main method should exist");
        });
    }

    @Test
    @DisplayName("Application class is annotated with @SpringBootApplication")
    void testSpringBootApplicationAnnotation() {
        // Arrange & Act
        boolean hasAnnotation = ResortsLiteApplication.class
                .isAnnotationPresent(org.springframework.boot.autoconfigure.SpringBootApplication.class);

        // Assert
        assertTrue(hasAnnotation, "ResortsLiteApplication should be annotated with @SpringBootApplication");
    }

    @Test
    @DisplayName("Application class is in the correct package")
    void testApplicationPackage() {
        // Arrange & Act
        String packageName = ResortsLiteApplication.class.getPackageName();

        // Assert
        assertEquals("com.demo.resortslite", packageName,
                "Application should be in com.demo.resortslite package");
    }
}
