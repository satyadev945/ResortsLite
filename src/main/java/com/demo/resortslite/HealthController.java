package com.demo.resortslite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Health check controller for monitoring application status.
 * This is essential for container orchestration and load balancers.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final Logger logger = LoggerFactory.getLogger(HealthController.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Basic health check endpoint
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));
        health.put("application", "ResortsLite");
        health.put("version", "1.0.0");
        
        return ResponseEntity.ok(health);
    }

    /**
     * Detailed health check with database connectivity
     */
    @GetMapping("/detailed")
    public ResponseEntity<Map<String, Object>> detailedHealth() {
        Map<String, Object> health = new HashMap<>();
        health.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));
        health.put("application", "ResortsLite");
        health.put("version", "1.0.0");
        
        // Check database connectivity
        Map<String, String> database = new HashMap<>();
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            database.put("status", "UP");
            database.put("type", "H2");
            logger.debug("Database health check: UP");
        } catch (Exception e) {
            database.put("status", "DOWN");
            database.put("error", e.getMessage());
            logger.error("Database health check failed", e);
        }
        
        health.put("database", database);
        
        // Overall status
        boolean isHealthy = "UP".equals(database.get("status"));
        health.put("status", isHealthy ? "UP" : "DOWN");
        
        return ResponseEntity.ok(health);
    }

    /**
     * Readiness probe for Kubernetes/container orchestration
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> readiness = new HashMap<>();
        
        try {
            // Check if database is accessible
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            readiness.put("status", "READY");
            readiness.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));
            return ResponseEntity.ok(readiness);
        } catch (Exception e) {
            logger.error("Readiness check failed", e);
            readiness.put("status", "NOT_READY");
            readiness.put("error", e.getMessage());
            return ResponseEntity.status(503).body(readiness);
        }
    }

    /**
     * Liveness probe for Kubernetes/container orchestration
     */
    @GetMapping("/live")
    public ResponseEntity<Map<String, Object>> live() {
        Map<String, Object> liveness = new HashMap<>();
        liveness.put("status", "ALIVE");
        liveness.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));
        return ResponseEntity.ok(liveness);
    }
}
