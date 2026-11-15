package net.knightsandkings.knk.paper.npc.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.junit.jupiter.api.Test;

import io.papermc.paper.block.fluid.FluidData;

/**
 * PandiO/knk-workspace#490: the Phase 1 test walkers took water as room for a body (water is passable), so some were
 * sent to the bottom of ponds and stood there drowning. A spot needs two passable, dry blocks above its floor, and dry
 * means no fluid at all: seagrass, kelp and bubble columns hold water but are neither {@code isLiquid()} nor
 * {@link Waterlogged} (the audit after the second commit still had {@code DROWNING 1088}).
 */
class NpcTestWalkersTest {

    /** Kept in a field (CLAUDE.md: never an inline mock World). */
    private final World world = mock(World.class);

    private static Block block(boolean passable, boolean liquid) {
        Block block = mock(Block.class);
        when(block.isPassable()).thenReturn(passable);
        when(block.isLiquid()).thenReturn(liquid);
        return block;
    }

    private static FluidData fluid(int level) {
        FluidData data = mock(FluidData.class);
        when(data.getLevel()).thenReturn(level);
        return data;
    }

    private static final FluidData DRY = fluid(0);
    private static final FluidData WATER_SOURCE = fluid(8);

    @Test
    void airAndGrassAreRoomForABody() {
        assertTrue(NpcTestWalkers.roomForBody(block(true, false), DRY));
    }

    @Test
    void waterAndLavaAreNot() {
        assertFalse(NpcTestWalkers.roomForBody(block(true, true), WATER_SOURCE));
    }

    @Test
    void aWaterloggedPlantIsNot() {
        Block seagrassLike = block(true, false);
        Waterlogged data = mock(Waterlogged.class);
        when(data.isWaterlogged()).thenReturn(true);
        when(seagrassLike.getBlockData()).thenReturn(data);
        assertFalse(NpcTestWalkers.roomForBody(seagrassLike, DRY));
    }

    /** Tall seagrass on a pond bottom: passable, not liquid, not Waterlogged, but it holds a water source. */
    @Test
    void seagrassKelpAndBubbleColumnsAreNot() {
        assertFalse(NpcTestWalkers.roomForBody(block(true, false), WATER_SOURCE));
        assertFalse(NpcTestWalkers.roomForBody(block(true, false), fluid(3)), "flowing water too");
    }

    @Test
    void aSolidBlockIsNot() {
        assertFalse(NpcTestWalkers.roomForBody(block(false, false), DRY));
    }

    /** The second #490 commit's hole: a sand floor under two blocks of tall seagrass passed as a dry spot. */
    @Test
    void aPondBottomWithTallSeagrassIsNotStandable() {
        Block sand = mock(Block.class);
        when(sand.isSolid()).thenReturn(true);
        Block seagrassLower = block(true, false);
        Block seagrassUpper = block(true, false);
        when(world.getBlockAt(0, 60, 0)).thenReturn(sand);
        when(world.getBlockAt(0, 61, 0)).thenReturn(seagrassLower);
        when(world.getBlockAt(0, 62, 0)).thenReturn(seagrassUpper);
        when(world.getFluidData(0, 61, 0)).thenReturn(WATER_SOURCE);
        when(world.getFluidData(0, 62, 0)).thenReturn(WATER_SOURCE);

        assertFalse(NpcTestWalkers.standable(world, 0, 60, 0));
    }

    @Test
    void grassUnderAirIsStandable() {
        Block grass = mock(Block.class);
        when(grass.isSolid()).thenReturn(true);
        Block air1 = block(true, false);
        Block air2 = block(true, false);
        when(world.getBlockAt(0, 60, 0)).thenReturn(grass);
        when(world.getBlockAt(0, 61, 0)).thenReturn(air1);
        when(world.getBlockAt(0, 62, 0)).thenReturn(air2);
        when(world.getFluidData(0, 61, 0)).thenReturn(DRY);
        when(world.getFluidData(0, 62, 0)).thenReturn(DRY);

        assertTrue(NpcTestWalkers.standable(world, 0, 60, 0));
    }
}
