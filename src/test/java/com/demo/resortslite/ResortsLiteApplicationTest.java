package com.demo.resortslite;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

class ResortsLiteApplicationTest {

    @Test
    void testSpringBootApplicationAnnotationPresent() {
        // Verify that the @SpringBootApplication annotation is present
        assertTrue(ResortsLiteApplication.class.isAnnotationPresent(
                org.springframework.boot.autoconfigure.SpringBootApplication.class));
    }

    @Test
    void testMainMethodExists() {
        // Verify that the main method exists
        try {
            ResortsLiteApplication.class.getMethod("main", String[].class);
            assertTrue(true);
        } catch (NoSuchMethodException e) {
            fail("Main method not found");
        }
    }

    @Test
    void testMainMethodIsPublicStatic() throws NoSuchMethodException {
        // Verify that the main method has correct signature
        var mainMethod = ResortsLiteApplication.class.getMethod("main", String[].class);
        assertNotNull(mainMethod);
        assertTrue(Modifier.isPublic(mainMethod.getModifiers()));
        assertTrue(Modifier.isStatic(mainMethod.getModifiers()));
    }

    @Test
    void testMainMethodReturnsVoid() throws NoSuchMethodException {
        // Verify that the main method returns void
        var mainMethod = ResortsLiteApplication.class.getMethod("main", String[].class);
        assertEquals(void.class, mainMethod.getReturnType());
    }

    @Test
    void testApplicationClassIsPublic() {
        // Verify that the application class is public
        assertTrue(Modifier.isPublic(ResortsLiteApplication.class.getModifiers()));
    }

    @Test
    void testApplicationClassHasDefaultConstructor() {
        // Verify that the application class can be instantiated
        assertDoesNotThrow(() -> new ResortsLiteApplication());
    }
}
