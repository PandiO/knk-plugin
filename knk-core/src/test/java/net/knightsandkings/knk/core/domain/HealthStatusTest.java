package net.knightsandkings.knk.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HealthStatusTest {
    
    @Test
    void shouldCreateHealthStatus() {
        HealthStatus status = new HealthStatus("UP", "1.0.0");
        assertEquals("UP", status.status());
        assertEquals("1.0.0", status.version());
    }
    
    @Test
    void shouldAllowNullVersion() {
        HealthStatus status = new HealthStatus("UP", null);
        assertEquals("UP", status.status());
        assertNull(status.version());
    }
    
    @Test
    void shouldRejectNullStatus() {
        assertThrows(IllegalArgumentException.class, () -> {
            new HealthStatus(null, "1.0.0");
        });
    }
    
    @Test
    void shouldRejectBlankStatus() {
        assertThrows(IllegalArgumentException.class, () -> {
            new HealthStatus("  ", "1.0.0");
        });
    }
    
    @Test
    void shouldDetectHealthyStatus() {
        assertTrue(new HealthStatus("UP", null).isHealthy());
        assertTrue(new HealthStatus("up", null).isHealthy());
        assertTrue(new HealthStatus("OK", null).isHealthy());
        assertTrue(new HealthStatus("ok", null).isHealthy());
        assertFalse(new HealthStatus("DOWN", null).isHealthy());
    }

    @Test
    void shouldReadTheApiReadinessValuesCaseInsensitively() {
        assertTrue(new HealthStatus("healthy", null).isHealthy());
        assertTrue(new HealthStatus("Healthy", null).isHealthy());
        assertTrue(new HealthStatus("degraded", null).isHealthy());
        assertTrue(new HealthStatus("DEGRADED", null).isDegraded());
        assertFalse(new HealthStatus("healthy", null).isDegraded());
        assertFalse(new HealthStatus("unhealthy", null).isHealthy());
        assertFalse(new HealthStatus("Unhealthy", null).isHealthy());
        assertFalse(new HealthStatus("DOWN", null).isHealthy());
    }
}
