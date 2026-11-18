package net.knightsandkings.knk.core.npc.nav;

import static net.knightsandkings.knk.core.npc.nav.FakeNavWorld.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.navigation.shape.CorridorShaper;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.RouteFollower.Kind;
import net.knightsandkings.knk.core.npc.nav.RouteFollower.Stretch;

/**
 * Link C1 (PandiO/knk-workspace#700): the cells of a walk the walk search planned are handed out as they are, with no
 * corridor shaping and no corner clearance, each on its own floor; the road part of the same line keeps both.
 */
class RouteFollowerWalkedTest {

    private final FakeNavWorld world = new FakeNavWorld();
    private final RouteFollower.Steering steering = new RouteFollower.Steering(new CorridorShaper(() -> 0L, null),
        new CorridorShaper.Options(4, 0.8));

    /** A walk up a stair that turns twice in a 1-wide shaft: (0,0) to (0,4), to (4,4), to (4,0), a block up per cell. */
    private static List<NavPoint> spiral() {
        List<NavPoint> out = new ArrayList<>();
        int y = 64;
        for (int z = 0; z <= 4; z++) {
            out.add(floor(0.5, y++, z + 0.5));
        }
        for (int x = 1; x <= 4; x++) {
            out.add(floor(x + 0.5, y++, 4.5));
        }
        for (int z = 3; z >= 0; z--) {
            out.add(floor(4.5, y++, z + 0.5));
        }
        return out;
    }

    private void buildSpiral(List<NavPoint> cells) {
        world.floor = (x, z) -> 40; // nothing at the stair's heights but the steps
        for (NavPoint c : cells) {
            world.addFloor(c.blockX(), c.blockY(), c.blockZ());
        }
    }

    @Test
    void aWalkedStairIsHandedOutCellByCellOnItsOwnFloors() {
        List<NavPoint> cells = spiral();
        buildSpiral(cells);
        boolean[] walked = new boolean[cells.size()];
        java.util.Arrays.fill(walked, true);
        RouteFollower follower = new RouteFollower(cells, List.of(), walked);

        Stretch stretch = follower.next(world, 32, 64, 1.0, steering);

        assertEquals(Kind.WAYPOINTS, stretch.kind());
        List<String> got = new ArrayList<>();
        for (NavPoint p : stretch.waypoints()) {
            got.add(p.blockX() + " " + (p.blockY() - 1) + " " + p.blockZ());
        }
        List<String> want = new ArrayList<>();
        for (NavPoint c : cells) {
            want.add(c.blockX() + " " + c.blockY() + " " + c.blockZ());
        }
        assertEquals(want, got, "every step, at its own height, nothing pulled straight across the shaft");
        for (int i = 0; i < follower.line().size(); i++) {
            assertTrue(follower.walked(i));
        }
    }

    @Test
    void theRoadPartOfTheLineIsNotWalkedAndPointsBetweenTwoWalkedOnesAre() {
        List<NavPoint> line = List.of(floor(0.5, 64, 0.5), floor(1.5, 64, 0.5), floor(10.5, 64, 0.5),
            floor(20.5, 64, 0.5));
        RouteFollower follower = new RouteFollower(line, List.of(), new boolean[] {true, true, false, false});

        assertTrue(follower.walked(0));
        assertTrue(follower.walked(1));
        int roadStart = follower.indexNearest(floor(1.5, 64, 0.5));
        assertFalse(follower.walked(roadStart + 1), "the points added on the road are road");
        assertFalse(follower.walked(follower.line().size() - 1));
        assertFalse(follower.walked(-1));
        assertFalse(new RouteFollower(line).walked(0), "no flags: nothing walked");
        assertEquals(new RouteFollower(line).line(), follower.line(), "the same points as without flags");
    }

    @Test
    void oneFlagPerPoint() {
        assertThrows(IllegalArgumentException.class,
            () -> new RouteFollower(List.of(floor(0.5, 64, 0.5)), List.of(), new boolean[2]));
    }
}
