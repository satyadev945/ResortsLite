package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ResortsLiteApplication}.
 * Verifies that the Spring application context loads successfully.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResortsLiteApplicationTest {

    @Test
    void contextLoads() {
        // Assert — Spring context should load without exceptions
        // This test verifies the application wiring is correct
        assertTrue(true, "Spring application context should load successfully");
    }

    @Test
    void mainMethod_doesNotThrow() {
        // Assert — main method should be callable without throwing
        assertDoesNotThrow(() -> ResortsLiteApplication.main(new String[]{}),
                "main() should not throw an exception");
    }
}
