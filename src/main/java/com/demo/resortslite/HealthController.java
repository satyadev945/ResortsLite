package com.demo.resortslite;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Health check endpoint for container orchestration liveness and readiness probes.
 * Exposes GET /health returning HTTP 200 with application status.
 * Spring Boot Actuator also provides /actuator/health via spring-boot-starter-actuator.
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("application", "ResortsLite");
        return ResponseEntity.ok(status);
    }
}
