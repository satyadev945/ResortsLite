package com.demo.resortslite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CalculatorTest {

    @Test
    void addsPositiveNumbers() {
        assertEquals(5, Calculator.add(2, 3));
    }

    @Test
    void addsNegativeNumbers() {
        assertEquals(-5, Calculator.add(-2, -3));
    }

    @Test
    void addsZero() {
        assertEquals(0, Calculator.add(0, 0));
        assertEquals(7, Calculator.add(7, 0));
    }

    @Test
    void addsMixedSigns() {
        assertEquals(-1, Calculator.add(2, -3));
        assertEquals(1, Calculator.add(-2, 3));
    }
}
