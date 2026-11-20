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

    private static Block door(boolean open) {
        org.bukkit.block.data.type.Door data = mock(org.bukkit.block.data.type.Door.class);
        when(data.isOpen()).thenReturn(open);
        Block block = mock(Block.class);
        when(block.isPassable()).thenReturn(false);
        when(block.getBlockData()).thenReturn(data);
        return block;
    }

    private static Block trapdoor() {
        org.bukkit.block.data.type.TrapDoor data = mock(org.bukkit.block.data.type.TrapDoor.class);
        when(data.isOpen()).thenReturn(true);
        Block block = mock(Block.class);
        when(block.isPassable()).thenReturn(false);
        when(block.getBlockData()).thenReturn(data);
        return block;
    }

    @Test
    void anOpenDoorIsPassedAClosedOneAndAnOpenTrapdoorAreNot() {
        // link C1 (PandiO/knk-workspace#700): the keep tower's open door at 1405 104 -494
        assertFalse(BukkitNavWorld.blocksBody(door(true)), "an open door's panel stands at the side");
        assertTrue(BukkitNavWorld.passable(door(true)));
        assertTrue(BukkitNavWorld.blocksBody(door(false)), "a closed door is a wall (the engine opens it on a leg)");
        assertTrue(BukkitNavWorld.blocksBody(trapdoor()), "an open trapdoor's panel can lie across the way");
        assertEquals(4.0, freeHeight(solid(), Map.of(1, door(true), 2, door(true))), 1e-9,
            "an open doorway has its headroom");
    }
    // ---- link H1 (PandiO/knk-workspace#779): ladders and roof hatches, as the walk search's cells ----

    private static Block ladder() {
        Block block = mock(Block.class);
        when(block.isPassable()).thenReturn(false); // a ladder has a collision box: its panel
        when(block.getType()).thenReturn(org.bukkit.Material.LADDER);
        when(block.getBlockData()).thenReturn(mock(BlockData.class));
        return block;
    }

    private static Block trapdoor(org.bukkit.Material material, boolean open, Block below) {
        org.bukkit.block.data.type.TrapDoor data = mock(org.bukkit.block.data.type.TrapDoor.class);
        when(data.isOpen()).thenReturn(open);
        Block block = mock(Block.class);
        when(block.isPassable()).thenReturn(false);
        when(block.getType()).thenReturn(material);
        when(block.getBlockData()).thenReturn(data);
        when(block.getRelative(org.bukkit.block.BlockFace.DOWN)).thenReturn(below);
        return block;
    }

    private static Block stone() {
        Block block = solid();
        when(block.getType()).thenReturn(org.bukkit.Material.STONE);
        return block;
    }

    private static final java.util.Set<String> LADDERS = java.util.Set.of("LADDER");

    @Test
    void aLadderIsClimbedNotAWall() {
        assertTrue(BukkitNavWorld.climbable(ladder(), LADDERS));
        assertTrue(BukkitNavWorld.passable(ladder()), "its panel stands at the side of the cell");
        assertFalse(BukkitNavWorld.blocksBody(ladder()));
        assertFalse(BukkitNavWorld.climbable(ladder(), java.util.Set.of()), "only the climbables given");
    }

    @Test
    void anOpenHatchOverALadderIsClimbedAsTheWalkSearchSays() {
        Block hatch = trapdoor(org.bukkit.Material.OAK_TRAPDOOR, true, ladder());
        assertTrue(BukkitNavWorld.climbable(hatch, LADDERS));
        assertTrue(BukkitNavWorld.passable(hatch));
        assertFalse(BukkitNavWorld.blocksBody(hatch), "the NPC climbs through it (the body check passes it)");
        assertFalse(BukkitNavWorld.closedHatch(hatch, LADDERS), "it is open");
        assertEquals(4.0, freeHeight(ladder(), Map.of(1, hatch)), 1e-9, "no ceiling over the ladder's top");
    }

    @Test
    void aClosedHatchStaysSolidAndIsAHatchToOpen() {
        Block hatch = trapdoor(org.bukkit.Material.SPRUCE_TRAPDOOR, false, ladder());
        assertFalse(BukkitNavWorld.climbable(hatch, LADDERS));
        assertTrue(BukkitNavWorld.blocksBody(hatch), "solid, as in the walk search");
        assertTrue(BukkitNavWorld.closedHatch(hatch, LADDERS), "an NPC with open-doors opens it");
        assertFalse(BukkitNavWorld.closedHatch(trapdoor(org.bukkit.Material.IRON_TRAPDOOR, false, ladder()), LADDERS),
            "an iron hatch needs redstone");
    }

    @Test
    void aTrapdoorWithoutALadderBelowIsNoHatch() {
        Block open = trapdoor(org.bukkit.Material.OAK_TRAPDOOR, true, stone());
        assertFalse(BukkitNavWorld.climbable(open, LADDERS));
        assertTrue(BukkitNavWorld.blocksBody(open), "its panel can lie across the way (C1's rule stands)");
        assertFalse(BukkitNavWorld.closedHatch(trapdoor(org.bukkit.Material.OAK_TRAPDOOR, false, stone()), LADDERS));
    }
}
