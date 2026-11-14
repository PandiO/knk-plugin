package net.knightsandkings.knk.paper.npc.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.junit.jupiter.api.Test;

/**
 * PandiO/knk-workspace#490: the Phase 1 test walkers took water as room for a body (water is passable), so some were
 * sent to the bottom of ponds and stood there drowning. A spot needs two passable, dry blocks above its floor.
 */
class NpcTestWalkersTest {

    private static Block block(boolean passable, boolean liquid) {
        Block block = mock(Block.class);
        when(block.isPassable()).thenReturn(passable);
        when(block.isLiquid()).thenReturn(liquid);
        return block;
    }

    @Test
    void airAndGrassAreRoomForABody() {
        assertTrue(NpcTestWalkers.roomForBody(block(true, false)));
    }

    @Test
    void waterAndLavaAreNot() {
        assertFalse(NpcTestWalkers.roomForBody(block(true, true)));
    }

    @Test
    void aWaterloggedPlantIsNot() {
        Block seagrassLike = block(true, false);
        Waterlogged data = mock(Waterlogged.class);
        when(data.isWaterlogged()).thenReturn(true);
        when(seagrassLike.getBlockData()).thenReturn(data);
        assertFalse(NpcTestWalkers.roomForBody(seagrassLike));
    }

    @Test
    void aSolidBlockIsNot() {
        assertFalse(NpcTestWalkers.roomForBody(block(false, false)));
    }
}
