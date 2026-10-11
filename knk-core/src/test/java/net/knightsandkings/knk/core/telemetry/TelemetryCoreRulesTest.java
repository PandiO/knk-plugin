package net.knightsandkings.knk.core.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pure telemetry rules (KNG-34 link 6): the emitter config (baseline for everyone, enhanced only for
 * targets), correlation scopes and their propagation to worker threads, and route templates that
 * never carry ids, names or query strings.
 */
class TelemetryCoreRulesTest {

    private static TelemetryClientConfig config(boolean enabled, Set<Integer> users, List<Integer> runs, Set<Integer> enhancedRuns) {
        return new TelemetryClientConfig(enabled, users, runs, enhancedRuns,
            Set.of("session.join", "menu.opened"), Set.of("movement.sample"));
    }

    @Test
    void initialConfig_allowsBaselineOnly() {
        TelemetryClientConfig initial = TelemetryClientConfig.initial();

        assertTrue(initial.allows("anything.baseline", TelemetryEvent.Level.BASELINE, 7));
        assertFalse(initial.allows("movement.sample", TelemetryEvent.Level.ENHANCED, 7));
        assertNull(initial.currentTestRunId());
    }

    @Test
    void enhanced_onlyForNamedUsersOrEnhancedTestRuns() {
        TelemetryClientConfig byUser = config(true, Set.of(7), List.of(), Set.of());
        TelemetryClientConfig byRun = config(true, Set.of(), List.of(3, 2), Set.of(3));
        TelemetryClientConfig runWithoutEnhanced = config(true, Set.of(), List.of(3), Set.of());

        assertTrue(byUser.allows("movement.sample", TelemetryEvent.Level.ENHANCED, 7));
        assertFalse(byUser.allows("movement.sample", TelemetryEvent.Level.ENHANCED, 8));
        assertFalse(byUser.allows("movement.sample", TelemetryEvent.Level.ENHANCED, null));
        assertTrue(byRun.allows("movement.sample", TelemetryEvent.Level.ENHANCED, 8));
        assertEquals(3, byRun.currentTestRunId());
        assertFalse(runWithoutEnhanced.isEnhanced(8));
        assertFalse(byUser.allows("combat.hit", TelemetryEvent.Level.ENHANCED, 7), "unknown enhanced name");
    }

    @Test
    void disabledOrUnknownNames_areRefused() {
        assertFalse(config(false, Set.of(7), List.of(), Set.of()).allows("session.join", TelemetryEvent.Level.BASELINE, 7));
        assertFalse(config(false, Set.of(7), List.of(), Set.of()).isEnhanced(7));
        assertFalse(config(true, Set.of(), List.of(), Set.of()).allows("chat.message", TelemetryEvent.Level.BASELINE, 7));
        assertTrue(config(true, Set.of(), List.of(), Set.of()).allows("session.join", TelemetryEvent.Level.BASELINE, null));
    }

    @Test
    void eventNames_haveFixedLevels() {
        assertEquals(TelemetryEvent.Level.ENHANCED, TelemetryEventNames.levelOf(TelemetryEventNames.COMBAT_HIT));
        assertEquals(TelemetryEvent.Level.BASELINE, TelemetryEventNames.levelOf(TelemetryEventNames.SIEGE_MATCH_JOIN));
        assertEquals("succeeded", TelemetryEvent.Outcome.SUCCEEDED.apiName());
        assertEquals("enhanced", TelemetryEvent.Level.ENHANCED.apiName());
    }

    @Test
    void correlation_isScoped_andRestored() {
        assertNull(TelemetryCorrelation.current());
        String inner = TelemetryCorrelation.with("outer", () -> {
            String nested = TelemetryCorrelation.with("inner", TelemetryCorrelation::current);
            assertEquals("outer", TelemetryCorrelation.current());
            return nested;
        });
        assertEquals("inner", inner);
        assertNull(TelemetryCorrelation.current());

        try (TelemetryCorrelation.Scope ignored = TelemetryCorrelation.open("cmd")) {
            assertEquals("cmd", TelemetryCorrelation.current());
        }
        assertNull(TelemetryCorrelation.current());
    }

    @Test
    void correlation_followsTasksToWorkerThreads() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var propagating = TelemetryCorrelation.propagating(pool);
            CompletableFuture<String> correlated = TelemetryCorrelation.with("action-1",
                () -> CompletableFuture.supplyAsync(TelemetryCorrelation::current, propagating));
            CompletableFuture<String> plain = CompletableFuture.supplyAsync(TelemetryCorrelation::current, propagating);

            assertEquals("action-1", correlated.get(5, TimeUnit.SECONDS));
            assertNull(plain.get(5, TimeUnit.SECONDS), "the worker thread doesn't keep the id");
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "http://api.test/api/Users/42/balances?x=1 | /api/Users/{id}/balances",
        "http://api.test/api/users/username/alice  | /api/users/username/{id}",
        "http://api.test/api/Users/uuid/0f1e2d3c-aaaa-bbbb-cccc-000000000001 | /api/Users/uuid/{id}",
        "http://api.test/api/players/by-name/Bob   | /api/players/by-name/{id}",
        "http://api.test/api/siege-matches/12/participants/7/left | /api/siege-matches/{id}/participants/{id}/left",
        "http://api.test/api/Users/by-link-code/ABCDEF | /api/Users/by-link-code/{id}",
        "/api/statistics/batches#frag              | /api/statistics/batches",
        "http://api.test                           | /"
    })
    void routeTemplates_dropValuesAndQueries(String url, String template) {
        assertEquals(template, ApiRouteTemplates.template(url));
    }

    @Test
    void routeTemplates_handleNothing() {
        assertEquals("unknown", ApiRouteTemplates.template(null));
        assertEquals("unknown", ApiRouteTemplates.template(" "));
    }
}
