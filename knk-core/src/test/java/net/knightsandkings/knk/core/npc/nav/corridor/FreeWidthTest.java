package net.knightsandkings.knk.core.npc.nav.corridor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavWorld;

/** The free width across a line for the trace's {@code # corridor:} lines (plan 4c.4 item 1, link M1). */
class FreeWidthTest {

    private static final String W = "w";

    /** Floor 64 everywhere except the holes; walls one and two blocks high stand on it. */
    private static final class World implements NavWorld {
        final Set<Long> holes = new HashSet<>();
        final Set<Long> walls = new HashSet<>();
        final Set<Long> steps = new HashSet<>();

        @Override
        public boolean isLoaded(String world, int x, int z) {
            return true;
        }

        @Override
        public boolean isStandableFloor(String world, int x, int floorY, int z) {
            long k = key(x, z);
            if (walls.contains(k)) {
                return false;
            }
            if (steps.contains(k)) {
                return floorY == 65;
            }
            return floorY == 64 && !holes.contains(k);
        }

        @Override
        public Set<String> regionsAt(String world, int x, int feetY, int z) {
            return Set.of();
        }

        @Override
        public boolean blocksBody(String world, int x, int y, int z) {
            return walls.contains(key(x, z)) && (y == 65 || y == 66);
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private final World world = new World();

    private static NavPoint feet(double x, double z) {
        return new NavPoint(W, x, 65, z);
    }

    @Test
    void aThreeWideRoadBetweenWallsIsThreeWide() {
        for (int x = -10; x <= 10; x++) {
            world.walls.add(key(x, -2));
            world.walls.add(key(x, 2));
        }

        FreeWidth w = FreeWidth.at(world, feet(0.5, 0.3), 1, 0);

        assertEquals(3.0, w.total(), 1e-9);
        assertEquals(1.3, w.left(), 1e-9, "east: left is -z, the wall's face at z -1");
        assertEquals(1.7, w.right(), 1e-9, "right is +z, the wall's face at z 2");
        assertEquals("3.00 1.30 1.70", w.describe());
    }

    @Test
    void aDropCountsLikeAWallAndOpenGroundIsCapped() {
        for (int x = -10; x <= 10; x++) {
            world.holes.add(key(x, 3)); // a ledge: no floor from z 3 on
        }

        FreeWidth w = FreeWidth.at(world, feet(0.5, 0.5), 1, 0);

        assertEquals(2.5, w.right(), 1e-9, "the ledge at z 3");
        assertEquals(FreeWidth.MAX_HALF, w.left(), 1e-9, "open to the left");
    }

    @Test
    void aStepBesideTheRoadIsNoWallButAFullBlockIs() {
        world.steps.add(key(0, 1));
        world.walls.add(key(0, 2));

        FreeWidth w = FreeWidth.at(world, feet(0.5, 0.5), 1, 0);

        assertEquals(1.5, w.right(), 1e-9, "a block up is walked on; the full-height wall behind it stops the body");
    }

    @Test
    void aDiagonalRayStopsAtTheFaceOfTheFirstCellItEnters() {
        world.walls.add(key(-1, 1));

        FreeWidth w = FreeWidth.at(world, feet(0.5, 0.3), 1, 1); // heading south-east: right is south-west

        assertEquals(0.7 / Math.sqrt(0.5), w.right(), 1e-9, "the ray enters cell (-1, 1) through its face at z 1");
        assertEquals(FreeWidth.MAX_HALF, w.left(), 1e-9);
    }

    @Test
    void aPointInAWallHasNoRoomAndEachPointUsesItsNeighboursForTheHeading() {
        world.walls.add(key(1, 0));
        List<FreeWidth> line = FreeWidth.along(world, List.of(feet(0.5, 0.5), feet(1.5, 0.5), feet(1.5, 3.5)));

        assertEquals(0, line.get(1).total(), 1e-9, "the middle point stands in the wall");
        assertEquals(FreeWidth.MAX_HALF, line.get(0).left(), 1e-9);
        assertEquals(FreeWidth.MAX_HALF * 2, line.get(2).total(), 1e-9, "heading south from the point before");
    }
}
