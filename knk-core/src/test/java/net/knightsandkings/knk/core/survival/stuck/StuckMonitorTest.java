package net.knightsandkings.knk.core.survival.stuck;

import net.knightsandkings.knk.core.survival.SurvivalPoint;
import net.knightsandkings.knk.core.survival.wave.PlannedEnemy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN §3c, S43: stuck respawn, last enemies, respawn limit, round backstop, telemetry. */
class StuckMonitorTest {
    private static final SurvivalPoint P = new SurvivalPoint(10, 64, 10);
    private final List<StuckEvent> events = new ArrayList<>();
    private final StuckMonitor monitor = new StuckMonitor(StuckSettings.defaults(), events::add);
    private final PlannedEnemy enemy = new PlannedEnemy(7, 3, "ZOMBIE", false);

    private static ProgressSample sample(long at, double distance, boolean damage, boolean blocked) {
        return ProgressSample.of(at, P, "gate-1", distance, damage, blocked);
    }

    @Test
    void noProgressForThirtySecondsRespawnsTheSameEnemyOnce() {
        monitor.track(enemy, 0);
        assertEquals(StuckDecision.None.INSTANCE, monitor.report(7, sample(1_000, 20, false, false)));
        assertEquals(StuckDecision.None.INSTANCE, monitor.report(7, sample(29_999, 20, false, false)));
        StuckDecision decision = monitor.report(7, sample(30_000, 20, false, false));
        assertEquals(new StuckDecision.Respawn(7, 1), decision);
        assertEquals(1, events.size());
        StuckEvent event = events.get(0);
        assertEquals(StuckEvent.Kind.STUCK_RESPAWN, event.kind());
        assertEquals(7, event.enemyId());
        assertEquals(3, event.round());
        assertEquals(Optional.of(P), event.lastPosition());
        assertEquals(Optional.of("gate-1"), event.target());
        assertEquals(1, event.respawnCount());
        assertFalse(event.isSystemKill());
        assertTrue(monitor.isTracked(7), "a respawn is not a death");
    }

    @Test
    void damageOrDistanceReductionCountsAsProgressANewTargetOnlyResetsTheBaseline() {
        monitor.track(enemy, 0);
        monitor.report(7, sample(0, 20, false, false));
        assertEquals(StuckDecision.None.INSTANCE, monitor.report(7, sample(25_000, 20, true, false)), "took damage");
        assertEquals(StuckDecision.None.INSTANCE, monitor.report(7, sample(50_000, 19, false, false)), "closed 1 block");
        assertEquals(StuckDecision.None.INSTANCE, monitor.report(7, sample(79_000, 18.5, false, false)), "half a block is not meaningful");
        assertEquals(new StuckDecision.Respawn(7, 1), monitor.report(7, sample(80_000, 18.5, false, false)));
        monitor.track(enemy, 80_000);
        assertEquals(StuckDecision.None.INSTANCE, monitor.report(7, sample(90_000, 40, false, false)), "baseline after the respawn");
        assertEquals(StuckDecision.None.INSTANCE,
            monitor.report(7, ProgressSample.of(100_000, P, "player-a", 30, false, false)), "new target: new baseline, no progress");
        assertEquals(StuckDecision.None.INSTANCE, monitor.report(7, ProgressSample.of(109_000, P, "player-a", 29.5, false, false)));
        assertEquals(new StuckDecision.Respawn(7, 2),
            monitor.report(7, ProgressSample.of(110_000, P, "player-a", 29.5, false, false)), "flipping targets while stuck stays stuck");
    }

    @Test
    void navigationBlockedNeverCountsAsProgressEvenWhenDistanceShrinks() {
        monitor.track(enemy, 0);
        monitor.report(7, sample(0, 20, false, true));
        monitor.report(7, sample(15_000, 10, false, true));
        assertEquals(new StuckDecision.Respawn(7, 1), monitor.report(7, sample(30_000, 5, false, true)));
    }

    @Test
    void respawnLimitLeadsToASystemKill() {
        monitor.track(enemy, 0);
        assertEquals(new StuckDecision.Respawn(7, 1), monitor.report(7, sample(30_000, 20, false, false)));
        monitor.track(enemy, 30_000);
        assertEquals(new StuckDecision.Respawn(7, 2), monitor.report(7, sample(60_000, 20, false, false)));
        monitor.track(enemy, 60_000);
        StuckDecision third = monitor.report(7, sample(90_000, 20, false, false));
        assertEquals(new StuckDecision.SystemKill(7, StuckEvent.Kind.RESPAWN_LIMIT_KILL), third);
        assertFalse(monitor.isTracked(7));
        assertEquals(StuckEvent.Kind.RESPAWN_LIMIT_KILL, events.get(2).kind());
        assertTrue(events.get(2).isSystemKill());
        assertEquals(2, events.get(2).respawnCount());
    }

    @Test
    void lastEnemiesGlowOnceWhenQueueExhaustedAndAtMostKAlive() {
        PlannedEnemy e8 = new PlannedEnemy(8, 3, "ZOMBIE", false);
        monitor.track(enemy, 0);
        monitor.track(e8, 0);
        assertTrue(monitor.lastEnemies(false, List.of(7L, 8L), 1_000).isEmpty(), "queue not exhausted");
        assertTrue(monitor.lastEnemies(true, List.of(7L, 8L, 9L, 10L), 1_000).isEmpty(), "4 > K");
        assertEquals(List.of(7L, 8L), monitor.lastEnemies(true, List.of(7L, 8L), 2_000));
        assertTrue(monitor.isGlowing(7) && monitor.pursuesDirectly(8));
        assertTrue(monitor.lastEnemies(true, List.of(7L, 8L), 3_000).isEmpty(), "flagged once");
        assertEquals(2, events.stream().filter(e -> e.kind() == StuckEvent.Kind.GLOW).count());
        monitor.forget(7);
        assertFalse(monitor.isGlowing(7));
    }

    @Test
    void backstopKillsTheRestOnceAfterTMinutesFromTheLastPlannedSpawn() {
        monitor.track(enemy, 0);
        assertTrue(monitor.backstop(OptionalLong.empty(), List.of(7L), 10 * 60_000).isEmpty(), "queue not exhausted");
        assertTrue(monitor.backstop(OptionalLong.of(60_000), List.of(7L), 60_000 + 5 * 60_000 - 1).isEmpty());
        assertEquals(List.of(7L), monitor.backstop(OptionalLong.of(60_000), List.of(7L), 60_000 + 5 * 60_000));
        assertTrue(monitor.backstopFired());
        assertEquals(StuckEvent.Kind.BACKSTOP_KILL, events.get(0).kind());
        assertTrue(monitor.backstop(OptionalLong.of(60_000), List.of(8L), 99 * 60_000).isEmpty(), "fires once per round");
    }

    @Test
    void backstopOffNeverFires() {
        StuckMonitor off = new StuckMonitor(StuckSettings.defaults().withoutBackstop(), events::add);
        off.track(enemy, 0);
        assertTrue(off.backstop(OptionalLong.of(0), List.of(7L), Long.MAX_VALUE / 2).isEmpty());
        assertTrue(events.isEmpty());
    }

    @Test
    void unknownEnemiesAreIgnored() {
        assertInstanceOf(StuckDecision.None.class, monitor.report(99, sample(100_000, 1, false, false)));
        assertEquals(0, monitor.respawnCount(99));
    }

    @Test
    void settingsDefaultsMatchTheDesign() {
        StuckSettings s = StuckSettings.defaults();
        assertEquals(30_000, s.stuckAfterMillis());
        assertEquals(3, s.lastEnemiesCount());
        assertEquals(2, s.respawnLimit());
        assertEquals(OptionalLong.of(300_000), s.roundBackstopMillis());
        assertTrue(s.backstopEnabled());
        assertFalse(s.withoutBackstop().backstopEnabled());
        assertEquals(OptionalLong.of(1), s.withBackstop(1).roundBackstopMillis());
    }
}
