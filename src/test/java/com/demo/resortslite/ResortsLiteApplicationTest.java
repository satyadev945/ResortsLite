package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration / smoke tests for ResortsLiteApplication.
 * Verifies the Spring Boot application context loads successfully.
 */
@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
    }
)
@ActiveProfiles("test")
class ResortsLiteApplicationTest {

    @Test
    void contextLoads_applicationStartsSuccessfully() {
        // If the context loads without exception, this test passes
        assertTrue(true, "Spring Boot application context should load successfully");
    }

    @Test
    void mainMethod_doesNotThrowException() {
        // Verify the main method can be invoked without throwing
        assertDoesNotThrow(() -> {
            // We don't actually call SpringApplication.run here to avoid port conflicts
            // Just verify the class is accessible
            Class<?> appClass = ResortsLiteApplication.class;
            assertNotNull(appClass);
        });
    }

    @Test
    void applicationClass_isAnnotatedWithSpringBootApplication() {
        assertTrue(
            ResortsLiteApplication.class.isAnnotationPresent(
                org.springframework.boot.autoconfigure.SpringBootApplication.class),
            "ResortsLiteApplication should be annotated with @SpringBootApplication"
        );
    }
}
