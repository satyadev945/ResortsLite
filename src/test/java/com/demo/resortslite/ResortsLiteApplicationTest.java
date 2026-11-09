package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Integration / smoke tests for {@link ResortsLiteApplication}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ResortsLiteApplicationTest {

    @Test
    void contextLoads_applicationContextStartsSuccessfully() {
        // If the Spring context fails to load, this test will fail automatically.
        // This verifies that all beans are correctly wired and the application can start.
    }

    @Test
    void main_doesNotThrowException() {
        // Verify that calling main() with an empty args array does not throw
        // (Spring Boot will start and immediately stop in test mode).
        assertDoesNotThrow(() ->
            ResortsLiteApplication.main(new String[]{"--spring.main.web-application-type=none"})
        );
    }
}
