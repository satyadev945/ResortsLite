package com.demo.resortslite;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

class ResortsLiteApplicationTest {

    @Test
    void constructor_createsInstance() {
        assertNotNull(new ResortsLiteApplication());
    }

    @Test
    void class_isAnnotatedWithSpringBootApplication() {
        assertTrue(ResortsLiteApplication.class.isAnnotationPresent(SpringBootApplication.class));
    }

    @Test
    void main_methodIsPublicStatic() throws Exception {
        Method m = ResortsLiteApplication.class.getMethod("main", String[].class);
        assertTrue(Modifier.isStatic(m.getModifiers()));
        assertTrue(Modifier.isPublic(m.getModifiers()));
        assertEquals(void.class, m.getReturnType());
    }
}
