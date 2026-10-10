package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateSpatialIndex;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KNG-106: which cells a moving door is about to fill. The door is a 3-wide, 3-high portcullis at
 * x 0..2, y 64..66, z 0, sliding 3 blocks up to open over 60 frames (tick rate 1).
 */
class CollisionPredictorTest {

    private static CachedGateDoor portcullis(AnimationState state) {
        CachedGateDoor gate = new CachedGateDoor(1, 1, "TestGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(0, 64, 0), 3, 3, 3, 500.0, 500.0, true, false, true, 90, "south");
        gate.setUAxis(new Vector(1, 0, 0));
        gate.setVAxis(new Vector(0, 1, 0));
        gate.setNAxis(new Vector(0, 0, 1));
        gate.setMotionVector(new Vector(0, 3, 0));
        int id = 1;
        for (int u = 0; u < 3; u++) {
            for (int v = 0; v < 3; v++) {
                gate.addBlock(new BlockSnapshot(id++, new Vector(u, v, 0), 1, "stone", 0));
            }
        }
        gate.setCurrentState(state);
        return gate;
    }

    private static boolean has(Set<Long> cells, int x, int y, int z) {
        return cells.contains(GateSpatialIndex.packCell(x, y, z));
    }

    /** A player standing in the opening: feet at y 64, 1.8 high, in the middle column. */
    private static final BoundingBox PLAYER_IN_OPENING = new BoundingBox(1.2, 64, 0.2, 1.8, 65.8, 0.8);

    @Test
    void aClosingDoorPredictsTheCellsBelowItNotTheOnesItLeft() {
        // nearly closed: the bottom row is at y 64.15, about to land on y 64
        CachedGateDoor gate = portcullis(AnimationState.CLOSING);

        Set<Long> upcoming = CollisionPredictor.upcomingCells(gate, 3, true);

        assertTrue(has(upcoming, 1, 64, 0), "the door's bottom row lands here");
        assertTrue(CollisionPredictor.overlaps(PLAYER_IN_OPENING, upcoming));
    }

    @Test
    void anOpenGateThatStartsClosingWarnsBeforeItReachesTheOpening() {
        // just started closing: the bottom row is at y 67, the player's head at 65.8 is still free
        CachedGateDoor gate = portcullis(AnimationState.CLOSING);

        Set<Long> upcoming = CollisionPredictor.upcomingCells(gate, 60, true);
        Set<Long> remaining = CollisionPredictor.remainingCells(gate, 60, true);

        assertFalse(CollisionPredictor.overlaps(PLAYER_IN_OPENING, upcoming), "not yet in the way");
        assertTrue(CollisionPredictor.overlaps(PLAYER_IN_OPENING, remaining), "but the closing will reach the player");
        assertTrue(has(remaining, 1, 64, 0));
    }

    @Test
    void anOpeningDoorLooksUpwards() {
        CachedGateDoor gate = portcullis(AnimationState.OPENING);

        Set<Long> upcoming = CollisionPredictor.upcomingCells(gate, 30, true);

        // halfway open (rows at 65.5..67.5) and rising: the top row moves into 67, the bottom one
        // has left 64 behind
        assertTrue(has(upcoming, 1, 67, 0));
        assertFalse(has(upcoming, 1, 64, 0));
    }

    @Test
    void theRestingFrameIsIncludedWhenTheWindowReachesIt() {
        CachedGateDoor gate = portcullis(AnimationState.CLOSING);

        Set<Long> upcoming = CollisionPredictor.upcomingCells(gate, 1, true);

        for (int x = 0; x < 3; x++) {
            for (int y = 64; y < 67; y++) {
                assertTrue(has(upcoming, x, y, 0), "closed cell " + x + "," + y);
            }
        }
    }

    @Test
    void entitiesBesideTheDoorAreNotInTheWay() {
        CachedGateDoor gate = portcullis(AnimationState.CLOSING);
        Set<Long> upcoming = CollisionPredictor.upcomingCells(gate, 3, true);

        // in front of the door (z 1..1.6) and touching its face exactly
        assertFalse(CollisionPredictor.overlaps(new BoundingBox(1.2, 64, 1.0, 1.8, 65.8, 1.6), upcoming));
        assertFalse(CollisionPredictor.overlaps(new BoundingBox(3.0, 64, 0.2, 3.6, 65.8, 0.8), upcoming));
    }

    @Test
    void overlapsReadsTheEntitysBox() {
        CachedGateDoor gate = portcullis(AnimationState.CLOSING);
        Entity entity = mock(Entity.class);
        when(entity.getBoundingBox()).thenReturn(PLAYER_IN_OPENING);

        assertTrue(CollisionPredictor.overlaps(entity, CollisionPredictor.upcomingCells(gate, 3, true)));
        assertFalse(CollisionPredictor.overlaps((Entity) null, Set.of()));
    }

    @Test
    void boundsOfHoldsEveryCellIncludingNegativeCoordinates() {
        Set<Long> cells = Set.of(GateSpatialIndex.packCell(-5, -10, 3), GateSpatialIndex.packCell(2, 70, -8));

        BoundingBox box = CollisionPredictor.boundsOf(cells);

        assertEquals(-5, box.getMinX());
        assertEquals(-10, box.getMinY());
        assertEquals(-8, box.getMinZ());
        assertEquals(3, box.getMaxX());
        assertEquals(71, box.getMaxY());
        assertEquals(4, box.getMaxZ());
        assertNull(CollisionPredictor.boundsOf(Set.of()));
    }

    @Test
    void asFilterBlocksExactlyTheCells() {
        var filter = CollisionPredictor.asFilter(Set.of(GateSpatialIndex.packCell(1, 64, 0)));

        assertTrue(filter.blocked(1, 64, 0));
        assertFalse(filter.blocked(1, 65, 0));
    }
}
