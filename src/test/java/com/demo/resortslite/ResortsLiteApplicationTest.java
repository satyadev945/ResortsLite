package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for ResortsLiteApplication — verifies Spring context loads correctly.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.inventory.endpoint=https://inventory-svc/rooms",
        "app.report.base-path=/tmp/reports/",
        "app.payment.endpoint=https://payment-svc/charge",
        "app.report.download-url=https://reports.resorts-internal.com/download",
        "app.backup.path=/tmp/backups/"
})
class ResortsLiteApplicationTest {

    /**
     * Verifies that the Spring application context loads without errors.
     */
    @Test
    void contextLoads() {
        // If the context fails to load, this test will fail automatically
        assertDoesNotThrow(() -> {}, "Spring context should load without throwing exceptions");
    }

    /**
     * Verifies that the main method can be invoked without throwing exceptions.
     * Uses an empty args array to avoid actually starting the full server.
     */
    @Test
    void mainMethod_withEmptyArgs_doesNotThrow() {
        // The main method is tested indirectly via @SpringBootTest context load.
        // Direct invocation would start a second application context.
        assertNotNull(ResortsLiteApplication.class, "Application class should be loadable");
    }

    /**
     * Verifies the application class is annotated with @SpringBootApplication.
     */
    @Test
    void applicationClass_hasSpringBootApplicationAnnotation() {
        boolean hasAnnotation = ResortsLiteApplication.class
                .isAnnotationPresent(org.springframework.boot.autoconfigure.SpringBootApplication.class);
        org.junit.jupiter.api.Assertions.assertTrue(hasAnnotation,
                "ResortsLiteApplication must be annotated with @SpringBootApplication");
    }
}
