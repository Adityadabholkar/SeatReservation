package com.example.seats.controller;

import com.example.seats.service.HealthService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final HealthService health;

    public HealthController(HealthService health) {
        this.health = health;
    }

    /** Liveness: the process is up. Deliberately does not touch the database. */
    @GetMapping({"/health", "/health/live"})
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    /** Readiness: really checks the database; 503 (fails closed) when it is unreachable. */
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        boolean db = health.isDatabaseReachable();
        Map<String, Object> body = Map.of(
                "status", db ? "UP" : "DOWN",
                "checks", Map.of("database", db ? "UP" : "DOWN"));
        return ResponseEntity.status(db ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
