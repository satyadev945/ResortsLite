package com.demo.resortslite;

/**
 * Custom exception for booking-related errors.
 * This provides better error handling and allows for specific exception handling
 * in the controller layer.
 */
public class BookingException extends RuntimeException {

    private final String errorCode;

    public BookingException(String message) {
        super(message);
        this.errorCode = "BOOKING_ERROR";
    }

    public BookingException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public BookingException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "BOOKING_ERROR";
    }

    public BookingException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
