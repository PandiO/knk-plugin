package net.knightsandkings.knk.paper.siege.survival;

import com.destroystokyo.paper.entity.Pathfinder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NPC platform §6 on the v1 mover: re-path cadence, bounded retries then blocked, lane override with opt-in reroute. */
class MobMoverTest {

    private final World world = mock(World.class);
    private final Zombie mob = mock(Zombie.class);
    private final Pathfinder pathfinder = mock(Pathfinder.class);
    private final SurvivalSettings settings = new SurvivalSettings(null, 10, 20, 20, 2, 2.5, 2.5, 96, 2, 1000, 0);
    private final Location destination = new Location(world, 40, 64, 0);

    MobMoverTest() {
        when(mob.getPathfinder()).thenReturn(pathfinder);
        when(mob.isValid()).thenReturn(true);
        when(mob.getWorld()).thenReturn(world);
        when(mob.getLocation()).thenReturn(new Location(world, 0, 64, 0));
    }

    private void pathOk(boolean ok) {
        Pathfinder.PathResult result = mock(Pathfinder.PathResult.class);
        when(result.canReachFinalPoint()).thenReturn(ok);
        when(pathfinder.moveTo(any(Location.class), anyDouble())).thenReturn(ok);
        when(pathfinder.getCurrentPath()).thenReturn(ok ? result : null);
    }

    @Test
    void rePathsEveryRepathTicksAndReportsBlockedAfterTheRetryBudget() {
        pathOk(true);
        MobMover mover = new MobMover(mob, settings, List.of(), false);
        mover.moveTo("gate:7", destination);
        mover.tick();
        mover.tick();
        verify(pathfinder, times(1)).moveTo(eq(destination), eq(1.0));
        mover.tick();
        mover.tick();
        verify(pathfinder, times(2)).moveTo(eq(destination), eq(1.0));
        assertFalse(mover.blocked());

        pathOk(false);
        for (int i = 0; i < 4; i++) mover.tick(); // two more attempts fail: 2 failures = the budget
        assertFalse(mover.blocked());
        for (int i = 0; i < 2; i++) mover.tick(); // third failure
        assertTrue(mover.blocked());
        mover.acknowledgeBlocked();
        assertFalse(mover.blocked());
        assertEquals(0, mover.failures());

        mover.moveTo("objective:2", new Location(world, 60, 64, 0));
        assertEquals("objective:2", mover.destinationId().orElseThrow());
        assertEquals(0, mover.failures());
    }

    @Test
    void laneOverrideIsVisitedInOrderBeforeTheDestination() {
        pathOk(true);
        Location lane1 = new Location(world, 10, 64, 0);
        Location lane2 = new Location(world, 20, 64, 0);
        MobMover mover = new MobMover(mob, settings, List.of(lane1, lane2), false);
        mover.moveTo("gate:7", destination);
        mover.tick();
        mover.tick();
        verify(pathfinder).moveTo(eq(lane1), eq(1.0));
        assertEquals(0, mover.laneIndex());
        when(mob.getLocation()).thenReturn(new Location(world, 10, 64, 1));
        mover.tick();
        assertEquals(1, mover.laneIndex(), "within the lane arrive distance: next point");
        mover.tick();
        verify(pathfinder).moveTo(eq(lane2), eq(1.0));
        when(mob.getLocation()).thenReturn(new Location(world, 20, 64, 0));
        mover.tick();
        mover.tick();
        verify(pathfinder).moveTo(eq(destination), eq(1.0));
    }

    @Test
    void aBlockedLanePointBlocksUnlessRerouteIsOptedIn() {
        pathOk(false);
        Location lane1 = new Location(world, 10, 64, 0);
        MobMover strict = new MobMover(mob, settings, List.of(lane1), false);
        strict.moveTo("gate:7", destination);
        for (int i = 0; i < 6; i++) strict.tick();
        assertTrue(strict.blocked(), "the binding lane point is unreachable");

        MobMover reroute = new MobMover(mob, settings, List.of(lane1), true);
        reroute.moveTo("gate:7", destination);
        for (int i = 0; i < 6; i++) reroute.tick();
        assertFalse(reroute.blocked(), "opt-in fallback: the override is left for the destination");
        assertEquals(1, reroute.laneIndex());
        for (int i = 0; i < 6; i++) reroute.tick();
        assertTrue(reroute.blocked(), "the destination itself is unreachable too");
    }

    @Test
    void arrivedIsHorizontalWithinThreeBlocksVertically() {
        MobMover mover = new MobMover(mob, settings, List.of(), false);
        assertFalse(mover.arrived(2.5));
        mover.moveTo("gate:7", destination);
        when(mob.getLocation()).thenReturn(new Location(world, 38.5, 65, 0.5));
        assertTrue(mover.arrived(2.5));
        when(mob.getLocation()).thenReturn(new Location(world, 38.5, 70, 0.5));
        assertFalse(mover.arrived(2.5));
        mover.stop();
        verify(pathfinder).stopPathfinding();
        assertTrue(mover.destinationId().isEmpty());
    }
}
