package net.knightsandkings.knk.core.realtime;

import net.knightsandkings.knk.core.metrics.PrometheusWriter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#178: backoff, sequence check, resync hooks and stats of the realtime client. */
class RealtimeCoreTest {

    @Test
    void backoffFollowsTheStepsThenStaysAtTheCap() {
        ReconnectBackoff backoff = new ReconnectBackoff(ReconnectBackoff.DEFAULT_STEPS, 0.0, () -> 0.5);
        List<Long> seconds = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            seconds.add(backoff.nextDelay().toSeconds());
        }
        assertEquals(List.of(1L, 2L, 5L, 10L, 30L, 30L, 30L), seconds);
        assertEquals(7, backoff.attempts());

        backoff.reset();
        assertEquals(Duration.ofSeconds(1), backoff.nextDelay());
    }

    @Test
    void backoffJitterStaysWithinItsBand() {
        ReconnectBackoff low = new ReconnectBackoff(List.of(Duration.ofSeconds(10)), 0.2, () -> 0.0);
        ReconnectBackoff high = new ReconnectBackoff(List.of(Duration.ofSeconds(10)), 0.2, () -> 0.999999);
        assertEquals(8000, low.nextDelay().toMillis());
        assertTrue(high.nextDelay().toMillis() >= 11999);
    }

    @Test
    void backoffNeedsSteps() {
        assertThrows(IllegalArgumentException.class, () -> new ReconnectBackoff(List.of(), 0.1, Math::random));
    }

    @Test
    void sequenceTrackerSeesOrderGapsAndRepeats() {
        SequenceTracker tracker = new SequenceTracker();
        assertEquals(SequenceTracker.Outcome.IN_ORDER, tracker.observe(1));
        assertEquals(SequenceTracker.Outcome.IN_ORDER, tracker.observe(2));
        assertEquals(SequenceTracker.Outcome.DUPLICATE, tracker.observe(2));
        assertEquals(SequenceTracker.Outcome.GAP, tracker.observe(5));
        assertEquals(SequenceTracker.Outcome.DUPLICATE, tracker.observe(4));
        assertEquals(SequenceTracker.Outcome.IN_ORDER, tracker.observe(6));
        assertEquals(6, tracker.last());

        tracker.reset();
        assertEquals(SequenceTracker.Outcome.IN_ORDER, tracker.observe(1));
    }

    @Test
    void sequenceTrackerTreatsAFirstMessageAboveOneAsAGap() {
        assertEquals(SequenceTracker.Outcome.GAP, new SequenceTracker().observe(3));
    }

    @Test
    void resyncHooksRunInOrderAndAFailureDoesNotStopTheRest() {
        ResyncHooks hooks = new ResyncHooks();
        List<String> ran = new ArrayList<>();
        hooks.register("caches", () -> ran.add("caches"));
        hooks.register("broken", () -> {
            throw new IllegalStateException("boom");
        });
        hooks.register("domains", () -> ran.add("domains"));
        hooks.register("caches", () -> ran.add("caches v2"));

        List<String> failed = hooks.runAll();

        assertEquals(List.of("caches v2", "domains"), ran);
        assertEquals(List.of("broken"), failed);
        assertEquals(List.of("caches", "broken", "domains"), hooks.names());
        assertThrows(IllegalArgumentException.class, () -> hooks.register(" ", () -> { }));
    }

    @Test
    void statsCountConnectsDisconnectsAndMessages() {
        RealtimeStats stats = new RealtimeStats();
        Instant t0 = Instant.parse("2026-10-26T12:00:00Z");
        stats.state(RealtimeState.CONNECTING, t0);
        stats.attempt();
        stats.state(RealtimeState.CONNECTED, t0);
        stats.message("Welcome", 1);
        stats.message("Welcome", 2);
        stats.gap();
        stats.resync();
        stats.state(RealtimeState.WAITING, t0.plusSeconds(5));

        assertEquals(1, stats.connects());
        assertEquals(1, stats.disconnects());
        assertEquals(1, stats.attempts());
        assertEquals(2, stats.messagesTotal());
        assertEquals(2, stats.lastSeq());
        assertNull(stats.connectedSince());

        PrometheusWriter out = new PrometheusWriter();
        stats.collect(out);
        String text = out.render();
        assertTrue(text.contains("knk_realtime_state{state=\"waiting\"} 1"), text);
        assertTrue(text.contains("knk_realtime_messages_total{type=\"Welcome\"} 2"), text);
        assertTrue(text.contains("knk_realtime_gaps_total 1"), text);
    }
}
