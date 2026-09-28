package com.demo.resortslite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for the ResortsLite application.
 * This provides centralized exception handling and consistent error responses.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles BookingException with appropriate HTTP status codes
     */
    @ExceptionHandler(BookingException.class)
    public ResponseEntity<Map<String, Object>> handleBookingException(BookingException ex) {
        logger.error("BookingException occurred: {} - {}", ex.getErrorCode(), ex.getMessage());
        
        Map<String, Object> error = new HashMap<>();
        error.put("status", "error");
        error.put("errorCode", ex.getErrorCode());
        error.put("message", ex.getMessage());
        
        // Determine HTTP status based on error code
        HttpStatus status = "VALIDATION_ERROR".equals(ex.getErrorCode()) 
            ? HttpStatus.BAD_REQUEST 
            : HttpStatus.INTERNAL_SERVER_ERROR;
        
        return ResponseEntity.status(status).body(error);
    }

    /**
     * Handles IllegalArgumentException (validation errors)
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException ex) {
        logger.error("Validation error: {}", ex.getMessage());
        
        Map<String, Object> error = new HashMap<>();
        error.put("status", "error");
        error.put("errorCode", "VALIDATION_ERROR");
        error.put("message", ex.getMessage());
        
        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Handles all other exceptions
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        logger.error("Unexpected error occurred", ex);
        
        Map<String, Object> error = new HashMap<>();
        error.put("status", "error");
        error.put("errorCode", "INTERNAL_ERROR");
        error.put("message", "An unexpected error occurred. Please try again later.");
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
