package net.knightsandkings.knk.core.connectivity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ApiConnectivityTest {

    private static final Instant T0 = Instant.parse("2026-10-10T12:00:00Z");

    private static Instant t(int seconds) {
        return T0.plusSeconds(seconds);
    }

    private static ApiConnectivity upMachine() {
        ApiConnectivity c = new ApiConnectivity(3, 2, T0);
        c.recordSuccess(t(0), "healthy");
        assertEquals(ApiConnectivityState.UP, c.state());
        return c;
    }

    private static ApiConnectivity downMachine() {
        ApiConnectivity c = new ApiConnectivity(3, 2, T0);
        c.recordFailure(t(0), "ConnectException");
        assertEquals(ApiConnectivityState.DOWN, c.state());
        return c;
    }

    @Test
    void startsUnknownAndNotUp() {
        ApiConnectivity c = new ApiConnectivity(3, 2, T0);
        assertEquals(ApiConnectivityState.UNKNOWN, c.state());
        assertFalse(c.isUp());
        assertEquals(T0, c.snapshot().since());
        assertNull(c.snapshot().lastProbeAt());
    }

    @Test
    void firstSuccessFromUnknownGoesUpAtOnce() {
        ApiConnectivity c = new ApiConnectivity(3, 2, T0);
        ApiConnectivityTransition tr = c.recordSuccess(t(1), "healthy").orElseThrow();
        assertEquals(ApiConnectivityState.UNKNOWN, tr.from());
        assertEquals(ApiConnectivityState.UP, tr.to());
        assertFalse(tr.isRecovery());
        assertTrue(c.isUp());
    }

    @Test
    void firstFailureFromUnknownGoesDownAtOnce() {
        ApiConnectivity c = new ApiConnectivity(3, 2, T0);
        ApiConnectivityTransition tr = c.recordFailure(t(1), "HTTP 503").orElseThrow();
        assertEquals(ApiConnectivityState.UNKNOWN, tr.from());
        assertEquals(ApiConnectivityState.DOWN, tr.to());
        assertTrue(tr.isOutage());
    }

    @Test
    void upNeedsConsecutiveFailuresToGoDown() {
        ApiConnectivity c = upMachine();
        assertTrue(c.recordFailure(t(1), "x").isEmpty());
        assertTrue(c.recordFailure(t(2), "x").isEmpty());
        assertEquals(ApiConnectivityState.UP, c.state());
        Optional<ApiConnectivityTransition> tr = c.recordFailure(t(3), "timeout");
        assertTrue(tr.isPresent());
        assertEquals(ApiConnectivityState.UP, tr.get().from());
        assertEquals(ApiConnectivityState.DOWN, tr.get().to());
        assertEquals("timeout", tr.get().reason());
        assertEquals(t(3), c.snapshot().since());
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        ApiConnectivity c = upMachine();
        c.recordFailure(t(1), "x");
        c.recordFailure(t(2), "x");
        assertTrue(c.recordSuccess(t(3), "healthy").isEmpty());
        assertTrue(c.recordFailure(t(4), "x").isEmpty());
        assertTrue(c.recordFailure(t(5), "x").isEmpty());
        assertEquals(ApiConnectivityState.UP, c.state());
    }

    @Test
    void downNeedsConsecutiveSuccessesToRecover() {
        ApiConnectivity c = downMachine();
        assertTrue(c.recordSuccess(t(1), "healthy").isEmpty());
        assertEquals(ApiConnectivityState.DOWN, c.state());
        ApiConnectivityTransition tr = c.recordSuccess(t(2), "healthy").orElseThrow();
        assertTrue(tr.isRecovery());
        assertTrue(c.isUp());
    }

    @Test
    void aFailureResetsTheSuccessCount() {
        ApiConnectivity c = downMachine();
        c.recordSuccess(t(1), "healthy");
        assertTrue(c.recordFailure(t(2), "x").isEmpty());
        assertTrue(c.recordSuccess(t(3), "healthy").isEmpty());
        assertEquals(ApiConnectivityState.DOWN, c.state());
    }

    @Test
    void noTransitionWhileTheStateHolds() {
        ApiConnectivity up = upMachine();
        for (int i = 1; i <= 10; i++) {
            assertTrue(up.recordSuccess(t(i), "healthy").isEmpty());
        }
        ApiConnectivity down = downMachine();
        for (int i = 1; i <= 10; i++) {
            assertTrue(down.recordFailure(t(i), "x").isEmpty());
        }
    }

    @Test
    void thresholdsOfOneFlipOnEveryChange() {
        ApiConnectivity c = new ApiConnectivity(1, 1, T0);
        c.recordSuccess(t(0), "healthy");
        assertTrue(c.recordFailure(t(1), "x").isPresent());
        assertTrue(c.recordSuccess(t(2), "healthy").isPresent());
    }

    @Test
    void snapshotReportsTheLastProbe() {
        ApiConnectivity c = upMachine();
        c.recordFailure(t(5), "HTTP 503 unhealthy");
        ApiConnectivity.Snapshot s = c.snapshot();
        assertEquals(t(5), s.lastProbeAt());
        assertFalse(s.lastProbeSucceeded());
        assertEquals("HTTP 503 unhealthy", s.lastProbeDetail());
        assertEquals(1, s.consecutiveFailures());
        assertEquals(0, s.consecutiveSuccesses());
    }

    @Test
    void rejectsThresholdsBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new ApiConnectivity(0, 1, T0));
        assertThrows(IllegalArgumentException.class, () -> new ApiConnectivity(1, 0, T0));
    }
}
