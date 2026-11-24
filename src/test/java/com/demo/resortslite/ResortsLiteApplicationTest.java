package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.*;

class ResortsLiteApplicationTest {

    @Test
    void class_isAnnotatedAsSpringBootApplication() {
        assertTrue(ResortsLiteApplication.class.isAnnotationPresent(SpringBootApplication.class));
    }

    @Test
    void constructor_createsInstance() {
        assertNotNull(new ResortsLiteApplication());
    }

    @Test
    void main_methodExists() throws NoSuchMethodException {
        assertNotNull(ResortsLiteApplication.class.getMethod("main", String[].class));
    }
}
