package net.knightsandkings.knk.paper.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

/**
 * The walking surface of a floor block (link B4, PandiO/knk-workspace#673): the top of its collision boxes under the
 * body's footprint. The real blocks are checked by {@code npc-stairhouse-climb} and {@code npc-replay-quarry-to-keep}.
 */
class BukkitNavWorldSurfaceTest {

    private static final BoundingBox FULL = new BoundingBox(0, 0, 0, 1, 1, 1);
    private static final BoundingBox BOTTOM_SLAB = new BoundingBox(0, 0, 0, 1, 0.5, 1);
    /** A stair facing north: the step's half block, and the upper half over the north half (z 0 to 0.5). */
    private static final List<BoundingBox> STAIR_NORTH = List.of(new BoundingBox(0, 0, 0, 1, 0.5, 1),
        new BoundingBox(0, 0.5, 0, 1, 1, 0.5));

    @Test
    void aFullBlockIsWhole() {
        assertEquals(1.0, BukkitNavWorld.surfaceTop(List.of(FULL), 0.5, 0.5), 1e-9);
    }

    @Test
    void aBottomSlabIsHalfABlock() {
        assertEquals(0.5, BukkitNavWorld.surfaceTop(List.of(BOTTOM_SLAB), 0.5, 0.5), 1e-9);
    }

    @Test
    void aStairGivesThePartTheBodyStandsOn() {
        assertEquals(1.0, BukkitNavWorld.surfaceTop(STAIR_NORTH, 0.5, 0.5), 1e-9,
            "the middle: the footprint reaches the upper half");
        assertEquals(0.5, BukkitNavWorld.surfaceTop(STAIR_NORTH, 0.5, 0.9), 1e-9, "on the step, clear of the upper half");
    }

    @Test
    void noBoxOrATallOneCountsAsAWholeBlock() {
        assertEquals(1.0, BukkitNavWorld.surfaceTop(List.of(), 0.5, 0.5), 1e-9);
        assertEquals(1.0, BukkitNavWorld.surfaceTop(List.of(new BoundingBox(0, 0, 0, 1, 1.5, 1)), 0.5, 0.5), 1e-9,
            "a fence's 1.5 is no floor height navigation plans with");
    }
}
