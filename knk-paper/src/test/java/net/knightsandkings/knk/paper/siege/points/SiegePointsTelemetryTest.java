package net.knightsandkings.knk.paper.siege.points;

import net.knightsandkings.knk.core.siege.economy.SiegePointsEconomy.Credit;
import net.knightsandkings.knk.core.siege.economy.SiegePointsSource;
import net.knightsandkings.knk.core.telemetry.TelemetryBuffer;
import net.knightsandkings.knk.core.telemetry.TelemetryClientConfig;
import net.knightsandkings.knk.core.telemetry.TelemetryEvent;
import net.knightsandkings.knk.core.telemetry.TelemetryEventNames;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A11 / P27 (PandiO/knk-workspace#866): siege points earned go to the diagnostic telemetry, the frequent sources summed. */
class SiegePointsTelemetryTest {

    private static final UUID A1 = new UUID(0, 1);

    private final TelemetryBuffer buffer = new TelemetryBuffer(100);
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final SiegePointsTelemetry telemetry;
    private final SiegeLobbyRuntime lobby = mock(SiegeLobbyRuntime.class);

    SiegePointsTelemetryTest() {
        TelemetryEmitter emitter = new TelemetryEmitter(buffer, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), "paper", "1");
        emitter.setUserIds(id -> 7);
        emitter.updateConfig(new TelemetryClientConfig(true, Set.of(), List.of(), Set.of(), Set.of(), Set.of()));
        telemetry = new SiegePointsTelemetry(emitter, now::get);
        when(lobby.id()).thenReturn(3);
    }

    @Test
    void killsAndCapturesAreOneEventEach() {
        telemetry.earned(lobby, new Credit(A1, SiegePointsSource.KILL, 130, 1.3, 130));
        List<TelemetryEvent> events = buffer.drain(10);
        assertEquals(1, events.size());
        TelemetryEvent e = events.get(0);
        assertEquals(TelemetryEventNames.SIEGE_POINTS_EARNED, e.name());
        assertEquals("kill", e.payload().get("source"));
        assertEquals(130L, ((Number) e.payload().get("amount")).longValue());
        assertEquals(1.3, ((Number) e.payload().get("multiplier")).doubleValue(), 1e-9);
        assertEquals(3, ((Number) e.payload().get("lobbyId")).intValue());
        assertEquals(7, e.userId());
    }

    @Test
    void captureProgressAndGateDamageAreSummedAtMostOnceAMinuteAndAtTheMatchEnd() {
        for (int s = 0; s < 60; s++) {
            telemetry.earned(lobby, new Credit(A1, SiegePointsSource.CAPTURE_PROGRESS, 3, 1.0, 3L * s));
            now.addAndGet(1000);
        }
        assertEquals(0, buffer.drain(10).size());
        telemetry.earned(lobby, new Credit(A1, SiegePointsSource.CAPTURE_PROGRESS, 3, 1.0, 180));
        List<TelemetryEvent> events = buffer.drain(10);
        assertEquals(1, events.size());
        assertEquals("capture_progress", events.get(0).payload().get("source"));
        assertEquals(183L, ((Number) events.get(0).payload().get("amount")).longValue(), "61 credits of 3 in 60 s");
        assertEquals(61, ((Number) events.get(0).payload().get("count")).intValue());

        telemetry.earned(lobby, new Credit(A1, SiegePointsSource.GATE_DAMAGE, 10, 1.0, 190));
        telemetry.flush(lobby);
        events = buffer.drain(10);
        assertEquals(1, events.size());
        assertEquals("gate_damage", events.get(0).payload().get("source"));
    }
}
