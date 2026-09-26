package net.knightsandkings.knk.core.teleport;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeLocationFinderTest {

    /** A world of air with a stone floor at y = 63 (so standing at y = 64 is safe), plus overrides. */
    private static final class FakeWorld implements BlockProbe {
        private final Map<String, String> blocks = new HashMap<>();
        private final int floorY;
        private final int minY;
        private final int maxY;

        FakeWorld(int floorY, int minY, int maxY) {
            this.floorY = floorY;
            this.minY = minY;
            this.maxY = maxY;
        }

        FakeWorld set(int x, int y, int z, String type) {
            blocks.put(x + "," + y + "," + z, type);
            return this;
        }

        String type(int x, int y, int z) {
            String type = blocks.get(x + "," + y + "," + z);
            if (type != null) {
                return type;
            }
            return y <= floorY ? "STONE" : "AIR";
        }

        @Override
        public boolean isPassable(int x, int y, int z) {
            String type = type(x, y, z);
            return type.equals("AIR") || type.equals("LAVA") || type.equals("FIRE");
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            String type = type(x, y, z);
            return type.equals("STONE") || type.equals("MAGMA_BLOCK");
        }

        @Override
        public boolean isHazard(int x, int y, int z) {
            return SafeLocationFinder.HAZARD_MATERIALS.contains(type(x, y, z));
        }

        @Override
        public int minY() {
            return minY;
        }

        @Override
        public int maxY() {
            return maxY;
        }
    }

    @Test
    void exactSafeSpotIsKept() {
        FakeWorld world = new FakeWorld(63, -64, 320);

        assertEquals(Optional.of(new SafeLocationFinder.Spot(0, 64, 0)), SafeLocationFinder.find(world, 0, 64, 0, 3));
    }

    @Test
    void lavaBelowIsNotSafe() {
        FakeWorld world = new FakeWorld(63, -64, 320).set(0, 63, 0, "MAGMA_BLOCK");

        assertFalse(SafeLocationFinder.isSafe(world, 0, 64, 0));
        Optional<SafeLocationFinder.Spot> spot = SafeLocationFinder.find(world, 0, 64, 0, 3);
        assertTrue(spot.isPresent());
        assertEquals(64, spot.get().y());
        assertEquals(1, Math.max(Math.abs(spot.get().x()), Math.abs(spot.get().z())), "the nearest ring");
    }

    @Test
    void lavaAtTheFeetIsNotSafe() {
        FakeWorld world = new FakeWorld(63, -64, 320).set(0, 64, 0, "LAVA");

        assertFalse(SafeLocationFinder.isSafe(world, 0, 64, 0));
    }

    @Test
    void oneHighGapIsNotSafeAndTheSearchClimbsOnTop() {
        // A single block at head height: standing under it is a 1-high gap. dy +1 is inside the
        // block and dy -1 inside the floor, so the exact column's first safe spot is on top (dy +2).
        FakeWorld world = new FakeWorld(63, -64, 320).set(0, 65, 0, "STONE");

        assertFalse(SafeLocationFinder.isSafe(world, 0, 64, 0));
        assertEquals(Optional.of(new SafeLocationFinder.Spot(0, 66, 0)), SafeLocationFinder.find(world, 0, 64, 0, 3));
    }

    @Test
    void destinationInTheAirDropsToTheFloorWithinTwoBlocks() {
        FakeWorld world = new FakeWorld(63, -64, 320);

        assertEquals(Optional.of(new SafeLocationFinder.Spot(5, 64, 5)), SafeLocationFinder.find(world, 5, 66, 5, 0));
    }

    @Test
    void searchFindsARingTwoSpot() {
        FakeWorld world = new FakeWorld(63, -64, 320);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = 62; y <= 66; y++) {
                    world.set(x, y, z, "LAVA");
                }
            }
        }

        Optional<SafeLocationFinder.Spot> spot = SafeLocationFinder.find(world, 0, 64, 0, 3);

        assertTrue(spot.isPresent());
        assertEquals(2, Math.max(Math.abs(spot.get().x()), Math.abs(spot.get().z())));
    }

    @Test
    void nothingSafeWithinTheRadiusGivesUp() {
        FakeWorld world = new FakeWorld(63, -64, 320);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 60; y <= 68; y++) {
                    world.set(x, y, z, "LAVA");
                }
            }
        }

        assertTrue(SafeLocationFinder.find(world, 0, 64, 0, 2).isEmpty());
        assertTrue(SafeLocationFinder.find(world, 0, 64, 0, 3).isPresent(), "ring 3 is outside the lava");
    }

    @Test
    void spotsOutsideTheWorldHeightAreNeverSafe() {
        FakeWorld world = new FakeWorld(-65, -64, 320);

        assertFalse(SafeLocationFinder.isSafe(world, 0, -64, 0), "floor below the minimum height");
        FakeWorld top = new FakeWorld(318, -64, 320);
        assertFalse(SafeLocationFinder.isSafe(top, 0, 319, 0), "head above the maximum height");
    }
}
