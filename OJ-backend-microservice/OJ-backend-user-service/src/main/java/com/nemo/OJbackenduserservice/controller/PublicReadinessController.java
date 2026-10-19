package com.nemo.OJbackenduserservice.controller;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** Routed probe: verifies gateway -> user readiness without creating browser sessions. */
@RestController
public class PublicReadinessController {
    private final HealthEndpoint health;
    public PublicReadinessController(HealthEndpoint health) { this.health = health; }

    @GetMapping("/health/readiness")
    public ResponseEntity<Map<String, String>> readiness() {
        var component = health.healthForPath("readiness");
        boolean up = component != null && Status.UP.equals(component.getStatus());
        return ResponseEntity.status(up ? 200 : 503).body(Map.of("status", up ? "UP" : "DOWN"));
    }
}
