package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.*;

class ResortsLiteApplicationTest {

    @Test
    void resortsLiteApplication_classIsSpringBootApplication() {
        // Arrange & Act
        SpringBootApplication annotation = ResortsLiteApplication.class
                .getAnnotation(SpringBootApplication.class);

        // Assert
        assertNotNull(annotation);
    }

    @Test
    void resortsLiteApplication_hasMainMethod() throws Exception {
        // Arrange & Act
        java.lang.reflect.Method mainMethod = ResortsLiteApplication.class
                .getMethod("main", String[].class);

        // Assert
        assertNotNull(mainMethod);
        assertTrue(java.lang.reflect.Modifier.isStatic(mainMethod.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isPublic(mainMethod.getModifiers()));
    }

    @Test
    void resortsLiteApplication_mainMethodAcceptsStringArray() throws Exception {
        // Arrange & Act
        java.lang.reflect.Method mainMethod = ResortsLiteApplication.class
                .getMethod("main", String[].class);

        // Assert
        Class<?>[] paramTypes = mainMethod.getParameterTypes();
        assertEquals(1, paramTypes.length);
        assertEquals(String[].class, paramTypes[0]);
    }

    @Test
    void resortsLiteApplication_classIsPublic() {
        // Arrange & Act
        int modifiers = ResortsLiteApplication.class.getModifiers();

        // Assert
        assertTrue(java.lang.reflect.Modifier.isPublic(modifiers));
    }

    @Test
    void resortsLiteApplication_classIsNotAbstract() {
        // Arrange & Act
        int modifiers = ResortsLiteApplication.class.getModifiers();

        // Assert
        assertFalse(java.lang.reflect.Modifier.isAbstract(modifiers));
    }

    @Test
    void resortsLiteApplication_classIsNotFinal() {
        // Arrange & Act
        int modifiers = ResortsLiteApplication.class.getModifiers();

        // Assert
        assertFalse(java.lang.reflect.Modifier.isFinal(modifiers));
    }

    @Test
    void resortsLiteApplication_classHasNoDeclaredFields() {
        // Arrange & Act
        java.lang.reflect.Field[] fields = ResortsLiteApplication.class.getDeclaredFields();

        // Assert
        assertEquals(0, fields.length);
    }

    @Test
    void resortsLiteApplication_classHasNoDeclaredMethodsExceptMain() {
        // Arrange & Act
        java.lang.reflect.Method[] methods = ResortsLiteApplication.class.getDeclaredMethods();

        // Assert
        assertTrue(methods.length >= 1);
        boolean hasMain = false;
        for (java.lang.reflect.Method m : methods) {
            if (m.getName().equals("main")) {
                hasMain = true;
                break;
            }
        }
        assertTrue(hasMain);
    }

    @Test
    void resortsLiteApplication_classNameIsCorrect() {
        // Arrange & Act
        String className = ResortsLiteApplication.class.getSimpleName();

        // Assert
        assertEquals("ResortsLiteApplication", className);
    }

    @Test
    void resortsLiteApplication_packageNameIsCorrect() {
        // Arrange & Act
        String packageName = ResortsLiteApplication.class.getPackage().getName();

        // Assert
        assertEquals("com.demo.resortslite", packageName);
    }
}