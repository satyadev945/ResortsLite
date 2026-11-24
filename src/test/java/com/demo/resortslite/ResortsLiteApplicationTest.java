package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.*;

class ResortsLiteApplicationTest {

    @Test
    void class_hasSpringBootApplicationAnnotation() {
        assertNotNull(ResortsLiteApplication.class.getAnnotation(SpringBootApplication.class));
    }

    @Test
    void constructor_createsInstance() {
        assertNotNull(new ResortsLiteApplication());
    }

    @Test
    void main_methodExistsAndIsStatic() throws Exception {
        var m = ResortsLiteApplication.class.getMethod("main", String[].class);
        assertTrue(java.lang.reflect.Modifier.isStatic(m.getModifiers()));
    }

    @Test
    void main_runsWithMockedSpringApplication() {
        try (var mocked = org.mockito.Mockito.mockStatic(org.springframework.boot.SpringApplication.class)) {
            ResortsLiteApplication.main(new String[0]);
            mocked.verify(() -> org.springframework.boot.SpringApplication.run(
                    org.mockito.ArgumentMatchers.eq(ResortsLiteApplication.class),
                    org.mockito.ArgumentMatchers.any(String[].class)));
        }
    }
}
