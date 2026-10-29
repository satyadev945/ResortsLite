package com.demo.resortslite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for {@link ResortsLiteApplication}.
 * Verifies the application class can be instantiated and that main() is callable.
 */
class ResortsLiteApplicationTest {

    /**
     * Verifies that the application class can be instantiated without errors.
     */
    @Test
    void applicationClass_canBeInstantiated() {
        ResortsLiteApplication app = new ResortsLiteApplication();
        assertNotNull(app);
    }

    /**
     * Verifies that the main method exists and is accessible.
     * We do not actually start the Spring context here to avoid requiring a live DB.
     */
    @Test
    void applicationClass_hasMainMethod() throws Exception {
        // Verify the main method exists via reflection
        var mainMethod = ResortsLiteApplication.class.getMethod("main", String[].class);
        assertNotNull(mainMethod);
    }
}
