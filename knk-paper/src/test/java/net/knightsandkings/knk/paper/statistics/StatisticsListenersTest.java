package net.knightsandkings.knk.paper.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;
import net.knightsandkings.knk.core.statistics.StatisticsBuffer;
import net.knightsandkings.knk.paper.config.KnkConfig;

/**
 * Acceptance criteria 2-4 (IMPLEMENTATION_PLAN.md §8 link 3): session start/end incl. kicks, AFK
 * signals, distance per mode with the exclusions, highest survived fall. The mocked World is held in
 * a field (knk-plugin CLAUDE.md: never build a Location around an inline World mock).
 */
class StatisticsListenersTest {

    private static final Instant T0 = Instant.parse("2026-10-03T10:00:00Z");

    private final World world = mock(World.class);
    private final World otherWorld = mock(World.class);
    private final UUID uuid = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final AfkPresentation presentation = mock(AfkPresentation.class);
    private final StatisticsBuffer buffer = new StatisticsBuffer();
    private final MutableClock clock = new MutableClock(T0);
    private final StatisticsService service = new StatisticsService(KnkConfig.StatisticsConfig.defaults(), buffer,
            new StatisticsContextResolver(id -> false), presentation, clock);

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }

    StatisticsListenersTest() {
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getName()).thenReturn("Alice");
    }

    private Location at(double x, double y, double z) {
        return new Location(world, x, y, z, 0f, 0f);
    }

    private StatisticsBatch drain() {
        return buffer.drain(2_000, clock.instant(), UUID::randomUUID);
    }

    // ===== sessions =====

    @Test
    void aSessionStartsOnUserDataAndEndsAsAKickWhenKicked() {
        service.sessionStarted(player, 42);
        clock.advance(30);
        service.kicked(uuid);
        service.sessionEnded(player);

        StatisticsBatch batch = drain();

        assertEquals(StatisticsBatch.SessionType.START, batch.sessions().get(0).type());
        assertEquals(42, batch.sessions().get(0).userId());
        assertEquals(StatisticsBatch.EndReason.Kick, batch.sessions().get(1).endReason());
        assertEquals(1, batch.durations().size());
        verify(presentation).forget(uuid);
    }

    @Test
    void serverStopEndsSessionsAndRestoresMarkers() {
        service.sessionStarted(player, 42);
        clock.advance(10);
        service.endAll(id -> player);

        StatisticsBatch batch = drain();
        assertEquals(StatisticsBatch.EndReason.ServerStop, batch.sessions().get(1).endReason());
        verify(presentation).restoreAll(org.mockito.ArgumentMatchers.any());
    }

    // ===== AFK =====

    @Test
    void lookingAroundOrWalkingIsActivityButVehicleAndWaterMovementIsNot() {
        Location from = new Location(world, 0, 64, 0, 10f, 0f);
        assertTrue(AfkActivityListener.isActivity(from, new Location(world, 0, 64, 0, 11.5f, 0f), true, true), "yaw");
        assertTrue(AfkActivityListener.isActivity(from, new Location(world, 0, 64, 0, 10f, -2f), false, false), "pitch");
        assertFalse(AfkActivityListener.isActivity(from, new Location(world, 0, 64, 0, 10.5f, 0f), false, false), "under 1°");
        assertTrue(AfkActivityListener.isActivity(from, new Location(world, 1, 64, 0, 10f, 0f), false, false), "walking");
        assertFalse(AfkActivityListener.isActivity(from, new Location(world, 1, 64, 0, 10f, 0f), true, false), "in a vehicle");
        assertFalse(AfkActivityListener.isActivity(from, new Location(world, 1, 64, 0, 10f, 0f), false, true), "in water");
        assertFalse(AfkActivityListener.isActivity(from, new Location(world, 0, 63, 0, 10f, 0f), false, false), "falling only");
        assertEquals(2f, AfkActivityListener.angleDelta(359f, 1f), 1e-6);
    }

    @Test
    void theAfkCommandIsRecognisedWithOrWithoutNamespace() {
        assertTrue(AfkActivityListener.isAfkCommand("/afk"));
        assertTrue(AfkActivityListener.isAfkCommand("/AFK now"));
        assertTrue(AfkActivityListener.isAfkCommand("/knightsandkings:afk"));
        assertFalse(AfkActivityListener.isAfkCommand("/afkers"));
        assertFalse(AfkActivityListener.isAfkCommand("/msg bob hi"));
    }

    @Test
    void autoAfkShowsTheMessageAndActivityEndsIt() {
        service.sessionStarted(player, 42);
        clock.advance(299);
        service.checkIdle(id -> player);
        verify(presentation, never()).entered(player, false);

        clock.advance(1);
        service.checkIdle(id -> player);
        verify(presentation).entered(player, false);
        assertTrue(service.isAfk(uuid));

        AfkActivityListener listener = new AfkActivityListener(service, Runnable::run, clock);
        clock.advance(5);
        listener.onMove(new PlayerMoveEvent(player, new Location(world, 0, 64, 0, 0f, 0f), new Location(world, 0, 64, 0, 45f, 0f)));

        verify(presentation).left(player);
        assertFalse(service.isAfk(uuid));
    }

    @Test
    void walkingResetsTheIdleTimerAndRepeatedMovesWithinASecondAreThrottled() {
        service.sessionStarted(player, 42);
        AfkActivityListener listener = new AfkActivityListener(service, Runnable::run, clock);
        clock.advance(200);
        listener.onMove(new PlayerMoveEvent(player, at(0, 64, 0), at(1, 64, 0)));
        clock.advance(299);
        service.checkIdle(id -> player);
        assertFalse(service.isAfk(uuid), "the walk at 200 s reset the idle timer");

        // a second move in the same second as a forwarded one is dropped: the idle window keeps counting from 499 s
        MutableClock frozen = new MutableClock(clock.instant());
        AfkActivityListener throttled = new AfkActivityListener(service, Runnable::run, frozen);
        throttled.onMove(new PlayerMoveEvent(player, at(1, 64, 0), at(2, 64, 0)));
        clock.advance(100);
        throttled.onMove(new PlayerMoveEvent(player, at(2, 64, 0), at(3, 64, 0)));
        clock.advance(200);
        service.checkIdle(id -> player);
        assertTrue(service.isAfk(uuid), "the throttled move at 599 s was not a signal");
    }

    @Test
    void theAfkCommandTogglesAndAnswersWhenDisabled() {
        service.sessionStarted(player, 42);
        new net.knightsandkings.knk.paper.commands.AfkCommand(service, true).onCommand(player, null, "afk", new String[0]);
        verify(presentation).entered(player, true);
        assertTrue(service.isAfk(uuid));

        Player other = mock(Player.class);
        new net.knightsandkings.knk.paper.commands.AfkCommand(null, false).onCommand(other, null, "afk", new String[0]);
        verify(other).sendMessage(org.mockito.ArgumentMatchers.contains("AFK is disabled"));
    }

    // ===== movement =====

    @Test
    void walkingFlyingAndRidingCountPerMode() {
        service.sessionStarted(player, 42);
        MovementStatisticsListener listener = new MovementStatisticsListener(service, 10.0);

        listener.onMove(new PlayerMoveEvent(player, at(0, 64, 0), at(3, 64, 4)));
        when(player.isGliding()).thenReturn(true);
        listener.onMove(new PlayerMoveEvent(player, at(0, 100, 0), at(0, 100, 2)));
        when(player.isGliding()).thenReturn(false);

        Boat boat = mock(Boat.class);
        when(boat.getPassengers()).thenReturn(List.of(player));
        listener.onVehicleMove(new VehicleMoveEvent(boat, at(0, 62, 0), at(0, 62, 1.5)));

        service.accrueAll();
        StatisticsBatch batch = drain();

        assertEquals(List.of("distance.foot", "distance.flying", "distance.vehicle"),
                batch.counters().stream().map(StatisticsBatch.ValueEntry::metric).toList());
        assertEquals(5.0, batch.counters().get(0).value(), 1e-9);
        assertEquals(2.0, batch.counters().get(1).value(), 1e-9);
        assertEquals(1.5, batch.counters().get(2).value(), 1e-9);
    }

    @Test
    void swimmingAlsoCountsAsSwim() {
        service.sessionStarted(player, 42);
        when(player.isInWater()).thenReturn(true);
        new MovementStatisticsListener(service, 10.0).onMove(new PlayerMoveEvent(player, at(0, 60, 0), at(2, 60, 0)));

        service.accrueAll();
        StatisticsBatch batch = drain();

        assertEquals(List.of("distance.foot", "distance.swim"), batch.counters().stream().map(StatisticsBatch.ValueEntry::metric).toList());
    }

    @Test
    void longSegmentsWorldChangesExcludedModesAndAfkPlayersAreNotCounted() {
        service.sessionStarted(player, 42);
        MovementStatisticsListener listener = new MovementStatisticsListener(service, 10.0);

        listener.onMove(new PlayerMoveEvent(player, at(0, 64, 0), at(11, 64, 0)));
        listener.onMove(new PlayerMoveEvent(player, at(0, 64, 0), new Location(otherWorld, 1, 64, 0)));
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        listener.onMove(new PlayerMoveEvent(player, at(0, 64, 0), at(1, 64, 0)));
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        service.toggleAfk(player);
        listener.onMove(new PlayerMoveEvent(player, at(0, 64, 0), at(1, 64, 0)));

        service.accrueAll();
        StatisticsBatch batch = drain();

        assertTrue(batch == null || batch.counters().isEmpty());
    }

    @Test
    void movementWithoutASessionIsIgnored() {
        new MovementStatisticsListener(service, 10.0).onMove(new PlayerMoveEvent(player, at(0, 64, 0), at(1, 64, 0)));
        service.accrueAll();
        assertNull(drain());
    }

    // ===== falls =====

    /** Mocked: constructing a damage event needs the server's damage-type registry. */
    private static EntityDamageEvent damage(Player victim, EntityDamageEvent.DamageCause cause, double finalDamage) {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getCause()).thenReturn(cause);
        when(event.getEntity()).thenReturn(victim);
        when(event.getFinalDamage()).thenReturn(finalDamage);
        return event;
    }

    private static EntityDamageEvent fall(Player victim, double finalDamage) {
        return damage(victim, EntityDamageEvent.DamageCause.FALL, finalDamage);
    }

    @Test
    void aSurvivedFallIsRecordedAndAFatalOneIsNot() {
        service.sessionStarted(player, 42);
        FallStatisticsListener listener = new FallStatisticsListener(service);
        when(player.getFallDistance()).thenReturn(23.46f);
        when(player.getHealth()).thenReturn(20.0);
        listener.onDamage(fall(player, 19.0));
        when(player.getFallDistance()).thenReturn(80f);
        listener.onDamage(fall(player, 20.0));

        StatisticsBatch batch = drain();

        assertEquals(1, batch.records().size());
        assertEquals("highest_fall", batch.records().get(0).metric());
        assertEquals(23.5, batch.records().get(0).value(), 1e-9);
    }

    @Test
    void otherDamageIsNotAFall() {
        service.sessionStarted(player, 42);
        when(player.getFallDistance()).thenReturn(10f);
        when(player.getHealth()).thenReturn(20.0);
        new FallStatisticsListener(service).onDamage(damage(player, EntityDamageEvent.DamageCause.LAVA, 4.0));

        StatisticsBatch batch = drain();
        assertTrue(batch.records().isEmpty());
        verify(presentation, never()).entered(org.mockito.ArgumentMatchers.eq(player), anyBoolean());
    }
}
