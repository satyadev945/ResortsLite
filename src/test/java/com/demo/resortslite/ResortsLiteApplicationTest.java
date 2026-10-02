package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for {@link ResortsLiteApplication}.
 *
 * <p>These tests do NOT load a Spring context — they verify the class
 * structure and annotation metadata only, avoiding any database dependency.</p>
 */
class ResortsLiteApplicationTest {

    @Test
    void applicationClass_isAnnotatedWithSpringBootApplication() {
        // Assert
        assertNotNull(
            ResortsLiteApplication.class
                .getAnnotation(SpringBootApplication.class),
            "ResortsLiteApplication must be annotated with @SpringBootApplication"
        );
    }

    @Test
    void applicationClass_isLoadable() throws Exception {
        // Arrange / Act
        Class<?> clazz = Class.forName("com.demo.resortslite.ResortsLiteApplication");

        // Assert
        assertNotNull(clazz);
        assertEquals("ResortsLiteApplication", clazz.getSimpleName());
    }

    @Test
    void applicationClass_hasMainMethod() throws Exception {
        // Arrange
        Class<?> clazz = ResortsLiteApplication.class;

        // Act
        java.lang.reflect.Method main = clazz.getDeclaredMethod("main", String[].class);

        // Assert
        assertNotNull(main, "main(String[]) method must exist");
        assertTrue(
            java.lang.reflect.Modifier.isPublic(main.getModifiers()),
            "main method must be public"
        );
        assertTrue(
            java.lang.reflect.Modifier.isStatic(main.getModifiers()),
            "main method must be static"
        );
    }

    @Test
    void applicationClass_packageIsCorrect() {
        // Assert
        assertEquals(
            "com.demo.resortslite",
            ResortsLiteApplication.class.getPackageName()
        );
    }
}
