package com.demo.resortslite;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResortsLiteApplicationTest {

    @Test
    void constructor_createsInstance() {
        assertNotNull(new ResortsLiteApplication());
    }

    @Test
    void main_invokesSpringApplicationRun() {
        try (MockedStatic<SpringApplication> mocked = mockStatic(SpringApplication.class)) {
            mocked.when(() -> SpringApplication.run(eq(ResortsLiteApplication.class), any(String[].class)))
                    .thenReturn(null);
            ResortsLiteApplication.main(new String[]{});
            mocked.verify(() -> SpringApplication.run(eq(ResortsLiteApplication.class), any(String[].class)));
        }
    }
}
