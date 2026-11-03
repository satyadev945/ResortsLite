package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mockStatic;

class ResortsLiteApplicationTest {

    @Test
    void main_invokesSpringApplicationRun() {
        try (var springApplication = mockStatic(SpringApplication.class)) {
            springApplication.when(() -> SpringApplication.run(ResortsLiteApplication.class, new String[]{"arg1"})).thenReturn(null);

            assertDoesNotThrow(() -> ResortsLiteApplication.main(new String[]{"arg1"}));

            springApplication.verify(() -> SpringApplication.run(ResortsLiteApplication.class, new String[]{"arg1"}));
        }
    }
}
