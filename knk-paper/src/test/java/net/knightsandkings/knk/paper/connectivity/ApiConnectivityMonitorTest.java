package net.knightsandkings.knk.paper.connectivity;

import net.knightsandkings.knk.core.connectivity.ApiConnectivity;
import net.knightsandkings.knk.core.connectivity.ApiConnectivityState;
import net.knightsandkings.knk.core.connectivity.ApiConnectivityTransition;
import net.knightsandkings.knk.core.domain.HealthStatus;
import net.knightsandkings.knk.core.exception.ApiException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** KNG-115: probe results drive the state machine; transitions go through the main-thread executor. */
class ApiConnectivityMonitorTest {

    private final List<Runnable> mainThreadQueue = new ArrayList<>();
    private final List<ApiConnectivityTransition> published = new ArrayList<>();
    private final AtomicReference<CompletableFuture<HealthStatus>> next = new AtomicReference<>();
    private int calls;
    private final ApiConnectivity connectivity = new ApiConnectivity(2, 2, Instant.EPOCH);
    private final ApiConnectivityMonitor monitor = new ApiConnectivityMonitor(
        () -> {
            calls++;
            return next.get();
        },
        connectivity,
        mainThreadQueue::add,
        published::add,
        Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC)
    );

    private void probeWith(CompletableFuture<HealthStatus> result) {
        next.set(result);
        monitor.probe();
    }

    private void runMainThread() {
        List<Runnable> copy = new ArrayList<>(mainThreadQueue);
        mainThreadQueue.clear();
        copy.forEach(Runnable::run);
    }

    @Test
    void transitionsArePublishedOnlyViaTheMainThreadExecutor() {
        probeWith(CompletableFuture.completedFuture(new HealthStatus("healthy", null)));
        assertTrue(published.isEmpty(), "must not publish off the main thread");
        assertEquals(1, mainThreadQueue.size());
        runMainThread();
        assertEquals(1, published.size());
        assertEquals(ApiConnectivityState.UP, published.get(0).to());
    }

    @Test
    void noEventWithoutATransition() {
        probeWith(CompletableFuture.completedFuture(new HealthStatus("healthy", null)));
        runMainThread();
        probeWith(CompletableFuture.completedFuture(new HealthStatus("healthy", null)));
        probeWith(CompletableFuture.completedFuture(new HealthStatus("unhealthy", null)));
        assertTrue(mainThreadQueue.isEmpty());
        assertEquals(1, published.size());
    }

    @Test
    void unhealthyAndErrorsBothCountAsFailures() {
        probeWith(CompletableFuture.completedFuture(new HealthStatus("healthy", null)));
        probeWith(CompletableFuture.completedFuture(new HealthStatus("unhealthy", null)));
        probeWith(CompletableFuture.failedFuture(new ApiException("http://x/health/ready", "Failed to connect", new ConnectException("refused"))));
        runMainThread();
        assertEquals(2, published.size());
        ApiConnectivityTransition down = published.get(1);
        assertEquals(ApiConnectivityState.DOWN, down.to());
        assertEquals("ConnectException: refused", down.reason());
    }

    @Test
    void degradedKeepsTheApiUp() {
        probeWith(CompletableFuture.completedFuture(new HealthStatus("degraded", null)));
        runMainThread();
        assertEquals(ApiConnectivityState.UP, connectivity.state());
    }

    @Test
    void aProbeStillRunningIsNotOverlapped() {
        CompletableFuture<HealthStatus> pending = new CompletableFuture<>();
        probeWith(pending);
        probeWith(CompletableFuture.completedFuture(new HealthStatus("healthy", null)));
        assertEquals(1, calls);
        pending.complete(new HealthStatus("healthy", null));
        probeWith(CompletableFuture.completedFuture(new HealthStatus("healthy", null)));
        assertEquals(2, calls);
    }

    @Test
    void aProbeThatThrowsSynchronouslyIsAFailureAndReleasesTheSlot() {
        ApiConnectivityMonitor throwing = new ApiConnectivityMonitor(
            () -> { throw new java.util.concurrent.RejectedExecutionException("shut down"); },
            connectivity, mainThreadQueue::add, published::add, Clock.systemUTC());
        throwing.probe();
        throwing.probe();
        assertEquals(ApiConnectivityState.DOWN, connectivity.state());
        assertEquals(2, connectivity.snapshot().consecutiveFailures());
    }

    @Test
    void describesHttpFailuresByStatusCode() {
        assertEquals("HTTP 404", ApiConnectivityMonitor.describeFailure(null,
            new java.util.concurrent.CompletionException(new ApiException("u", 404, "Health check failed", ""))));
        assertEquals("unhealthy", ApiConnectivityMonitor.describeFailure(new HealthStatus("unhealthy", null), null));
    }

    @Test
    void settingsDefaultWhenTheSectionIsMissing() {
        assertEquals(ApiConnectivitySettings.defaults(), ApiConnectivitySettings.fromConfig(new YamlConfiguration()));
        assertEquals(ApiConnectivitySettings.defaults(), ApiConnectivitySettings.fromConfig(null));
    }

    @Test
    void settingsReadTheConfigAndClampToOne() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.connectivity.enabled", false);
        yaml.set("api.connectivity.probe-interval-seconds", 30);
        yaml.set("api.connectivity.probe-timeout-seconds", 2);
        yaml.set("api.connectivity.failures-to-down", 0);
        yaml.set("api.connectivity.successes-to-up", 4);
        yaml.set("api.connectivity.health-root-url", "http://api.lan:5294");
        ApiConnectivitySettings s = ApiConnectivitySettings.fromConfig(yaml);
        assertFalse(s.enabled());
        assertEquals(Duration.ofSeconds(30), s.probeInterval());
        assertEquals(Duration.ofSeconds(2), s.probeTimeout());
        assertEquals(1, s.failuresToDown());
        assertEquals(4, s.successesToUp());
        assertEquals("http://api.lan:5294", s.healthRootUrl());
    }
}
