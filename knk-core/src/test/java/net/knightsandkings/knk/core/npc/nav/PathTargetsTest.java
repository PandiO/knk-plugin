package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Targets on the NPC's own floor (implementation plan 4b.2 item 5; test plan item 16.3, the #492 cases). */
class PathTargetsTest {

    private final FakeNavWorld world = new FakeNavWorld();

    @Test
    void aLeaderBelowTheRoutesEndGetsARoadPlanNotAShortTrip() {
        // #492, third comment: 1425 48 -516 at the bottom of the stair house, the route's end 1421 85 -516 above it
        NavPoint leader = floor(1425.5, 47, -515.5);
        NavPoint end = floor(1421.5, 84, -515.5);

        assertFalse(PathTargets.shortTrip(leader, end, 16), "4 blocks across, 37 up: a trip for the road");
        assertTrue(PathTargets.shortTrip(leader, floor(1421.5, 48, -515.5), 16), "near and on about the same floor");
    }

    /**
     * A U-turn staircase where the upper flight runs straight above the lower one: lower floor 44 along x 0..10, a
     * stair up at x 10..15 (one block per block), the upper floor 50 back along x 15..0 in the same columns.
     */
    private List<NavPoint> stackedFlights() {
        List<NavPoint> path = new ArrayList<>();
        for (int x = 0; x <= 10; x++) {
            path.add(floor(x + 0.5, 44, 0.5));
        }
        for (int x = 11; x <= 15; x++) {
            path.add(floor(x + 0.5, 44 + (x - 10), 0.5));
        }
        for (int x = 15; x >= 0; x--) {
            path.add(floor(x + 0.5, 50, 0.5));
        }
        world.floor = (x, z) -> z == 0 && x >= 11 && x <= 15 ? 44 + (x - 10) : 44;
        for (int x = 0; x <= 15; x++) {
            world.addFloor(x, 50, 0);
        }
        return path;
    }

    @Test
    void stackedFlightsNeverGiveATargetOnAnotherFloor() {
        List<NavPoint> path = stackedFlights();

        PathTargets.Target lower = PathTargets.ahead(world, path, 3, path.size() - 1, 44, 16, null);

        assertNotNull(lower);
        assertTrue(lower.index() > 3, "ahead, never behind its place");
        assertEquals(44, lower.floor().blockY(), "on the lower flight the NPC stands on");
        assertTrue(lower.floor().x() - path.get(3).x() >= PathTargets.MIN_AHEAD - 1e-9, "at least 3 blocks along");

        // an NPC on the upper flight whose place is on the upper flight: its target stays up there
        int upperPlace = 16 + 4;
        PathTargets.Target upper = PathTargets.ahead(world, path, upperPlace, path.size() - 1, 50, 16, null);
        assertNotNull(upper);
        assertEquals(50, upper.floor().blockY());
    }

    @Test
    void aFollowerBesideALandingAimsAheadOnItsOwnFloorNotTheFlightAbove() {
        // #492: a follower at the landing 1433 44 -518; the group's path climbs 4 blocks past the landing's corner
        List<NavPoint> path = new ArrayList<>();
        for (int i = 0; i <= 3; i++) {
            path.add(floor(1433.5 - i, 43, -517.5));
        }
        for (int i = 1; i <= 6; i++) {
            path.add(floor(1430.5 - i, 43 + i, -517.5));
        }
        world.floor = (x, z) -> x <= 1429 && x >= 1424 ? 43 + (1430 - x) : 43;

        PathTargets.Target target = PathTargets.ahead(world, path, 0, path.size() - 1, 43, 14, null);

        assertNotNull(target);
        assertTrue(Math.abs(target.floor().y() - 43) <= HopPlanner.CONTINUITY_RANGE,
            "within reach of its floor, not 4 blocks up past the corner: " + target);
    }

    @Test
    void aPointWithoutAFloorWithinReachEndsTheSearch() {
        List<NavPoint> path = new ArrayList<>();
        for (int x = 0; x <= 10; x++) {
            path.add(floor(x + 0.5, 64, 0.5));
        }
        world.floor = (x, z) -> x == 2 ? 70 : 64; // a step six blocks up: another floor

        assertNull(PathTargets.ahead(world, path, 0, path.size() - 1, 64, 16, null));
    }

    @Test
    void theTargetRespectsTheRulesTheHeadroomAndTheLimits() {
        List<NavPoint> path = new ArrayList<>();
        for (int x = 0; x <= 30; x++) {
            path.add(floor(x + 0.5, 64, 0.5));
        }
        world.headroom.put("3,64,0", 1.5); // too low for a body
        world.regionsByColumn = (x, z) -> x == 4 ? java.util.Set.of("keep") : java.util.Set.of();

        PathTargets.Target target = PathTargets.ahead(world, path, 0, path.size() - 1, 64, 16,
            feet -> !world.regionsAt(feet.world(), feet.blockX(), feet.blockY(), feet.blockZ()).contains("keep"));

        assertNotNull(target);
        assertEquals(5, target.floor().blockX(), "past the low cell and the denied region");
        assertNull(PathTargets.ahead(world, path, 0, 2, 64, 16, null), "never past the last index (a follower's slot)");
        assertNull(PathTargets.ahead(world, path, 0, path.size() - 1, 64, 2, null), "never further than maxAlong");
        assertEquals(10, PathTargets.ahead(world, path, 0, 10, path.size() - 1, 64, 16, null).index(),
            "not before minIndex (a target that failed from here)");
    }
}
