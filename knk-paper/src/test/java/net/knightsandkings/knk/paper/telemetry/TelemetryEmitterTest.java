package net.knightsandkings.knk.paper.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.telemetry.TelemetryBuffer;
import net.knightsandkings.knk.core.telemetry.TelemetryClientConfig;
import net.knightsandkings.knk.core.telemetry.TelemetryCorrelation;
import net.knightsandkings.knk.core.telemetry.TelemetryEvent;
import net.knightsandkings.knk.core.telemetry.TelemetryEventNames;

/**
 * The plugin emitter (KNG-34 link 6, acceptance criterion 2): baseline events for everyone, enhanced
 * only for configured players/test runs, the switch, automatic test-run/correlation/session stamps,
 * and payload values that stay scalar and short.
 */
class TelemetryEmitterTest {

    static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");
    static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    final TelemetryBuffer buffer = new TelemetryBuffer(100);
    final TelemetryEmitter emitter = emitter(buffer);

    static TelemetryEmitter emitter(TelemetryBuffer buffer) {
        TelemetryEmitter emitter = new TelemetryEmitter(buffer, Clock.fixed(NOW, ZoneOffset.UTC), "paper 25565", "1.2.3");
        emitter.setUserIds(id -> ALICE.equals(id) ? 7 : BOB.equals(id) ? 8 : null);
        emitter.setSessionKeys(id -> ALICE.equals(id) ? SESSION : null);
        return emitter;
    }

    static TelemetryClientConfig config(Set<Integer> enhancedUsers, List<Integer> runs, Set<Integer> enhancedRuns) {
        return new TelemetryClientConfig(true, enhancedUsers, runs, enhancedRuns, Set.of(), Set.of());
    }

    TelemetryEvent only() {
        List<TelemetryEvent> events = buffer.drain(10);
        assertEquals(1, events.size());
        return events.get(0);
    }

    @Test
    void baselineEvents_areStamped() {
        emitter.updateConfig(config(Set.of(), List.of(4), Set.of()));

        boolean emitted = TelemetryCorrelation.with("corr-1", () -> emitter.event(TelemetryEventNames.SIEGE_MATCH_JOIN)
            .player(ALICE).match(12).outcome(TelemetryEvent.Outcome.SUCCEEDED).object("siege_lobby", 3).put("teamId", 2).emit());

        assertTrue(emitted);
        TelemetryEvent e = only();
        assertEquals("siege.match_join", e.name());
        assertEquals(TelemetryEvent.Level.BASELINE, e.level());
        assertEquals(7, e.userId());
        assertEquals(SESSION, e.sessionKey());
        assertEquals(4, e.testRunId());
        assertEquals(12, e.matchId());
        assertEquals("corr-1", e.correlationId());
        assertEquals("siege", e.feature());
        assertEquals("match_join", e.action());
        assertEquals("siege_lobby", e.objectType());
        assertEquals("3", e.objectId());
        assertEquals(NOW, e.occurredAt());
        assertEquals("paper 25565", e.serverName());
        assertEquals(1L, e.serverSeq());
        assertEquals(Map.of("teamId", 2), e.payload());
    }

    @Test
    void sequence_increasesPerEvent() {
        emitter.event(TelemetryEventNames.MENU_OPENED).player(ALICE).emit();
        emitter.event(TelemetryEventNames.MENU_OPENED).player(BOB).emit();

        assertEquals(List.of(1L, 2L), buffer.drain(10).stream().map(TelemetryEvent::serverSeq).toList());
    }

    @Test
    void enhancedEvents_onlyForTargets() {
        emitter.updateConfig(config(Set.of(7), List.of(), Set.of()));

        assertTrue(emitter.event(TelemetryEventNames.MOVEMENT_SAMPLE).player(ALICE).emit());
        assertFalse(emitter.event(TelemetryEventNames.MOVEMENT_SAMPLE).player(BOB).emit());
        assertTrue(emitter.isEnhanced(ALICE));
        assertFalse(emitter.isEnhanced(BOB));

        emitter.updateConfig(config(Set.of(), List.of(5), Set.of(5)));
        assertTrue(emitter.event(TelemetryEventNames.COMBAT_HIT).player(BOB).emit(), "an enhanced test run covers everyone");
        assertEquals(TelemetryEvent.Level.ENHANCED, buffer.drain(10).get(1).level());
    }

    @Test
    void theApiSwitch_stopsEverything() {
        emitter.updateConfig(new TelemetryClientConfig(false, Set.of(7), List.of(), Set.of(), Set.of(), Set.of()));

        assertFalse(emitter.event(TelemetryEventNames.SESSION_JOIN).player(ALICE).emit());
        assertEquals(0, buffer.size());
        emitter.updateConfig(null);
        assertFalse(emitter.config().enabled(), "a null config is ignored");
    }

    @Test
    void freeTextNeverBecomesACode_andPayloadsStayScalar() {
        emitter.event(TelemetryEventNames.COMMAND_RESULT).player(BOB)
            .reason("player typed something")
            .object("command", "pay bob 100")
            .correlation("not a token")
            .put("command", "x".repeat(200))
            .put("nested", Map.of("a", 1))
            .put("mode", TelemetryEvent.Outcome.DENIED)
            .put("skip", null)
            .emit();

        TelemetryEvent e = only();
        assertNull(e.reasonCode());
        assertNull(e.objectId());
        assertNull(e.correlationId());
        assertEquals(64, ((String) e.payload().get("command")).length());
        assertFalse(e.payload().containsKey("nested"));
        assertEquals("denied", e.payload().get("mode"));
        assertFalse(e.payload().containsKey("skip"));
        assertNull(e.sessionKey(), "bob has no statistics session");
    }

    @Test
    void failingLookups_neverBreakAnEvent() {
        emitter.setUserIds(id -> {
            throw new IllegalStateException("cache down");
        });
        emitter.setSessionKeys(id -> {
            throw new IllegalStateException("no statistics");
        });

        assertTrue(emitter.event(TelemetryEventNames.SESSION_LEAVE).player(ALICE).emit());
        TelemetryEvent e = only();
        assertNull(e.userId());
        assertNull(e.sessionKey());
    }

    @Test
    void droppedSummary_isABaselineEvent() {
        emitter.emitDropped(17, "buffer_full_or_send_failed");

        TelemetryEvent e = only();
        assertEquals(TelemetryEventNames.TELEMETRY_DROPPED, e.name());
        assertEquals(17L, e.payload().get("dropped"));
    }
}
