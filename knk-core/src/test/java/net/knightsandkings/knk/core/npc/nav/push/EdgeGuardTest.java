package net.knightsandkings.knk.core.npc.nav.push;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.navigation.shape.NavWorld;

/**
 * The edge guard (implementation plan 4c.4 item 7.2, link M7): a wall walk 3 wide at floor 70 with the courtyard 6
 * blocks below on its south side (z 3) and a parapet on its north side (z -1).
 */
class EdgeGuardTest {

    /** Floor 70 for z 0..2, the courtyard at 64 elsewhere; a parapet at z -1; a 2-block step down at x 10. */
    private static final class Wall implements NavWorld {
        final Set<String> walls = new HashSet<>();

        Wall() {
            for (int x = -5; x <= 15; x++) {
                walls.add(x + ",71,-1");
                walls.add(x + ",72,-1");
            }
        }

        int floor(int x, int z) {
            if (z >= 0 && z <= 2) {
                return x >= 10 ? 68 : 70;
            }
            return 64;
        }

        @Override
        public boolean isLoaded(String world, int x, int z) {
            return true;
        }

        @Override
        public boolean isStandableFloor(String world, int x, int floorY, int z) {
            return floor(x, z) == floorY && !walls.contains(x + "," + (floorY + 1) + "," + z);
        }

        @Override
        public Set<String> regionsAt(String world, int x, int feetY, int z) {
            return Set.of();
        }

        @Override
        public boolean blocksBody(String world, int x, int y, int z) {
            return walls.contains(x + "," + y + "," + z);
        }
    }

    private static NavPoint feet(double x, double z) {
        return new NavPoint("w", x, 71, z);
    }

    @Test
    void theCellOnTheCourtyardSideIsNextToADrop() {
        Wall w = new Wall();
        assertTrue(EdgeGuard.nextToDrop(w, feet(3.5, 2.5)));
        assertArrayEquals(new int[] {0, 1}, EdgeGuard.dropSide(w, feet(3.5, 2.5)), "the drop is south");
    }

    @Test
    void theMiddleOfTheWalkAndTheParapetSideAreNot() {
        Wall w = new Wall();
        assertFalse(EdgeGuard.nextToDrop(w, feet(3.5, 1.5)), "a cell of margin on both sides");
        assertFalse(EdgeGuard.nextToDrop(w, feet(3.5, 0.5)), "a parapet is a wall, not a drop");
        assertNull(EdgeGuard.dropSide(w, feet(3.5, 0.5)));
    }

    @Test
    void aTwoBlockStepDownIsNoDrop() {
        Wall w = new Wall();
        assertFalse(EdgeGuard.nextToDrop(w, feet(9.5, 1.5)), "the walk goes on two blocks lower: no fall");
    }

    @Test
    void towardsTheDropOnlyWhenTheMovePointsThere() {
        Wall w = new Wall();
        assertTrue(EdgeGuard.towardsDrop(w, feet(3.5, 1.6), feet(3.5, 2.4)), "pushed south, to the edge");
        assertFalse(EdgeGuard.towardsDrop(w, feet(2.5, 2.4), feet(3.5, 2.4)), "along the edge");
        assertFalse(EdgeGuard.towardsDrop(w, feet(3.5, 0.6), feet(3.5, 1.4)), "not next to a drop yet");
    }
}
