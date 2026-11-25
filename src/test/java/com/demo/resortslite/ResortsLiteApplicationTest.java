package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.*;

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
    void main_methodExistsAndIsPublicStatic() throws NoSuchMethodException {
        var m = ResortsLiteApplication.class.getMethod("main", String[].class);
        assertTrue(java.lang.reflect.Modifier.isStatic(m.getModifiers()));
    }
}
