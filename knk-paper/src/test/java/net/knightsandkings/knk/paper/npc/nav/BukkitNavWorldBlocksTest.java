package net.knightsandkings.knk.paper.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.junit.jupiter.api.Test;

/**
 * The block rules behind corner clearance, the low-headroom rule and "near a wall" (implementation plan 4b.2 items 1,
 * 4 and 13; test plan item 17). Mocked blocks: MockBukkit's {@code BlockMock} does not implement
 * {@code isPassable()}; the real blocks are checked by the {@code npc-corners-line} scenario on a Paper server.
 */
class BukkitNavWorldBlocksTest {

    private static Block air() {
        Block block = mock(Block.class);
        when(block.isPassable()).thenReturn(true);
        when(block.getBlockData()).thenReturn(mock(BlockData.class));
        return block;
    }

    private static Block solid() {
        Block block = mock(Block.class);
        when(block.isPassable()).thenReturn(false);
        when(block.getBlockData()).thenReturn(mock(BlockData.class));
        return block;
    }

    private static Block slab(Slab.Type type) {
        Slab data = mock(Slab.class);
        when(data.getType()).thenReturn(type);
        Block block = mock(Block.class);
        when(block.isPassable()).thenReturn(false);
        when(block.getBlockData()).thenReturn(data);
        return block;
    }

    private static Block stairs(Bisected.Half half) {
        Stairs data = mock(Stairs.class);
        when(data.getHalf()).thenReturn(half);
        Block block = mock(Block.class);
        when(block.isPassable()).thenReturn(false);
        when(block.getBlockData()).thenReturn(data);
        return block;
    }

    private static double freeHeight(Block floor, Map<Integer, Block> above) {
        return BukkitNavWorld.freeHeight(floor, k -> above.getOrDefault(k, air()));
    }

    @Test
    void aSlabInTheUpperHalfOfTheThirdBlockLeavesTwoAndAHalf() {
        // the landing at 1433 44 -517: an overhanging floor's slab in the upper half of the third block
        assertEquals(2.5, freeHeight(solid(), Map.of(3, slab(Slab.Type.TOP))), 1e-9);
        assertEquals(2.0, freeHeight(solid(), Map.of(3, solid())), 1e-9);
        assertEquals(4.0, freeHeight(solid(), Map.of()), 1e-9, "open sky: four blocks looked at");
        assertEquals(3.0, freeHeight(solid(), Map.of(4, slab(Slab.Type.BOTTOM))), 1e-9, "a bottom slab's bottom");
    }

    @Test
    void aBottomSlabFloorAddsHalfABlockOfHeadroom() {
        assertEquals(2.5, freeHeight(slab(Slab.Type.BOTTOM), Map.of(3, solid())), 1e-9);
    }

    @Test
    void wallsAndFencesBlockABodyStepsDoNot() {
        Block fence = solid(); // a fence: not passable, its collision box higher than a block
        assertTrue(BukkitNavWorld.blocksBody(solid()), "a full block");
        assertTrue(BukkitNavWorld.blocksBody(fence), "a fence");
        assertFalse(BukkitNavWorld.blocksBody(slab(Slab.Type.BOTTOM)), "a bottom slab is a step");
        assertFalse(BukkitNavWorld.blocksBody(stairs(Bisected.Half.BOTTOM)), "a stair is a step");
        assertTrue(BukkitNavWorld.blocksBody(stairs(Bisected.Half.TOP)), "an upside-down stair is not");
        assertTrue(BukkitNavWorld.blocksBody(slab(Slab.Type.TOP)), "a top slab at feet height stops a body");
        assertFalse(BukkitNavWorld.blocksBody(air()), "air");
    }
}
