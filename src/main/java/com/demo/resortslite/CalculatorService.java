package com.demo.resortslite;

import java.math.BigDecimal;
import java.util.Objects;

import org.springframework.stereotype.Service;

/**
 * Stateless, thread-safe calculator for exact decimal addition and subtraction.
 * Internal use only; not exposed over HTTP.
 */
@Service
public class CalculatorService {

    public BigDecimal add(BigDecimal a, BigDecimal b) {
        Objects.requireNonNull(a, "a must not be null");
        Objects.requireNonNull(b, "b must not be null");
        return a.add(b);
    }

    public BigDecimal subtract(BigDecimal a, BigDecimal b) {
        Objects.requireNonNull(a, "a must not be null");
        Objects.requireNonNull(b, "b must not be null");
        return a.subtract(b);
    }
}
