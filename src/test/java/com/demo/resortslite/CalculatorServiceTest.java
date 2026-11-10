package com.demo.resortslite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class CalculatorServiceTest {

    private final CalculatorService calc = new CalculatorService();

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void addIntegers() {
        assertEquals(0, bd("5").compareTo(calc.add(bd("2"), bd("3"))));
    }

    @Test
    void addDecimalsExactly() {
        assertEquals(0, bd("0.3").compareTo(calc.add(bd("0.1"), bd("0.2"))));
    }

    @Test
    void addNegatives() {
        assertEquals(0, bd("-5").compareTo(calc.add(bd("-2"), bd("-3"))));
    }

    @Test
    void addZero() {
        assertEquals(0, bd("7").compareTo(calc.add(bd("7"), BigDecimal.ZERO)));
    }

    @Test
    void subtractIntegers() {
        assertEquals(0, bd("1").compareTo(calc.subtract(bd("3"), bd("2"))));
    }

    @Test
    void subtractDecimalsExactly() {
        assertEquals(0, bd("0.1").compareTo(calc.subtract(bd("0.3"), bd("0.2"))));
    }

    @Test
    void subtractGoingNegative() {
        assertEquals(0, bd("-4").compareTo(calc.subtract(bd("1"), bd("5"))));
    }

    @Test
    void subtractNegatives() {
        assertEquals(0, bd("1").compareTo(calc.subtract(bd("-2"), bd("-3"))));
    }

    @Test
    void subtractZero() {
        assertEquals(0, bd("7").compareTo(calc.subtract(bd("7"), BigDecimal.ZERO)));
    }

    @Test
    void addNullA() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> calc.add(null, BigDecimal.ONE));
        assertEquals("a must not be null", e.getMessage());
    }

    @Test
    void addNullB() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> calc.add(BigDecimal.ONE, null));
        assertEquals("b must not be null", e.getMessage());
    }

    @Test
    void subtractNullA() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> calc.subtract(null, BigDecimal.ONE));
        assertEquals("a must not be null", e.getMessage());
    }

    @Test
    void subtractNullB() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> calc.subtract(BigDecimal.ONE, null));
        assertEquals("b must not be null", e.getMessage());
    }
}
