package com.nemo.OJbackenduserservice.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicReadinessControllerTest {
    @Test void reportsReadinessWithoutExposingDependencyDetails() {
        var health = mock(HealthEndpoint.class); var controller = new PublicReadinessController(health);
        when(health.healthForPath("readiness")).thenReturn(Health.up().withDetail("secret", "hidden").build());
        var response = controller.readiness();
        assertEquals(200, response.getStatusCode().value());
        assertEquals(java.util.Map.of("status", "UP"), response.getBody());
    }
    @Test void unavailableOrMissingReadinessIsUnhealthy() {
        var health = mock(HealthEndpoint.class); var controller = new PublicReadinessController(health);
        when(health.healthForPath("readiness")).thenReturn(Health.down().build());
        assertEquals(503, controller.readiness().getStatusCode().value());
        when(health.healthForPath("readiness")).thenReturn(null);
        assertEquals(503, controller.readiness().getStatusCode().value());
    }
}
