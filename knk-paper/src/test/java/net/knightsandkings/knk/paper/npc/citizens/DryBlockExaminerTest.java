package net.knightsandkings.knk.paper.npc.citizens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.Material;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import net.citizensnpcs.api.astar.pathfinder.BlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer.PassableState;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer.StandableState;
import net.citizensnpcs.api.astar.pathfinder.BlockSource;
import net.citizensnpcs.api.astar.pathfinder.PathPoint;

/**
 * PandiO/knk-workspace#490: Citizens' own examiner stands NPCs in and over water and passes them through it; the dry
 * examiner in front of it vetoes every wet cell, so a test walker's path never crosses a pond.
 */
class DryBlockExaminerTest {

    /** Everything is air except a pond: water at x = 5, y 60..62 (the floor is at y 59). */
    private static BlockSource pond(Material pondBlock) {
        BlockSource source = mock(BlockSource.class);
        when(source.getMaterialAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int x = inv.getArgument(0);
            int y = inv.getArgument(1);
            if (y < 60) {
                return x == 5 ? Material.SAND : Material.GRASS_BLOCK;
            }
            return x == 5 && y <= 62 ? pondBlock : Material.AIR;
        });
        return source;
    }

    private static PathPoint at(int x, int y, int z, boolean start) {
        PathPoint point = mock(PathPoint.class);
        when(point.getVector()).thenReturn(new Vector(x, y, z));
        when(point.getParentPoint()).thenReturn(start ? null : mock(PathPoint.class));
        return point;
    }

    /** Citizens' MinecraftBlockExaminer as far as this test needs it: everything standable and passable. */
    private static BlockExaminer citizens() {
        BlockExaminer inner = mock(BlockExaminer.class);
        when(inner.canStandAt(any(), any())).thenReturn(StandableState.STANDABLE);
        when(inner.isPassable(any(), any())).thenReturn(PassableState.PASSABLE);
        return inner;
    }

    @Test
    void aPondBottomIsNotStandableAndWaterNotPassable() {
        DryBlockExaminer dry = new DryBlockExaminer(List.of(citizens()));
        BlockSource source = pond(Material.WATER);

        assertEquals(StandableState.NOT_STANDABLE, dry.canStandAt(source, at(5, 60, 0, false)), "on the bottom");
        assertEquals(PassableState.IMPASSABLE, dry.isPassable(source, at(5, 61, 0, false)));
    }

    @Test
    void theSurfaceOfAPondIsNotStandable() {
        DryBlockExaminer dry = new DryBlockExaminer(List.of(citizens()));

        assertEquals(StandableState.NOT_STANDABLE, dry.canStandAt(pond(Material.WATER), at(5, 63, 0, false)),
            "standing over water means sinking into it");
    }

    @Test
    void seagrassAndKelpCountAsWater() {
        DryBlockExaminer dry = new DryBlockExaminer(List.of(citizens()));

        assertEquals(StandableState.NOT_STANDABLE, dry.canStandAt(pond(Material.TALL_SEAGRASS), at(5, 60, 0, false)));
        assertEquals(PassableState.IMPASSABLE, dry.isPassable(pond(Material.KELP_PLANT), at(5, 61, 0, false)));
    }

    @Test
    void dryGroundIsLeftToCitizens() {
        DryBlockExaminer dry = new DryBlockExaminer(List.of(citizens()));
        BlockSource source = pond(Material.WATER);

        assertEquals(StandableState.STANDABLE, dry.canStandAt(source, at(3, 60, 0, false)));
        assertEquals(PassableState.PASSABLE, dry.isPassable(source, at(3, 61, 0, false)));
    }

    /** A walker already in water may path out: the search's start point is not vetoed. */
    @Test
    void theStartPointMayBeWet() {
        DryBlockExaminer dry = new DryBlockExaminer(List.of(citizens()));
        BlockSource source = pond(Material.WATER);

        assertEquals(StandableState.STANDABLE, dry.canStandAt(source, at(5, 60, 0, true)));
        assertEquals(PassableState.PASSABLE, dry.isPassable(source, at(5, 60, 0, true)));
    }

    @Test
    void wet_CoversLiquidsAndUnderwaterPlants() {
        assertTrue(DryBlockExaminer.wet(Material.WATER, null));
        assertTrue(DryBlockExaminer.wet(Material.LAVA, null));
        assertTrue(DryBlockExaminer.wet(Material.BUBBLE_COLUMN, null));
        assertTrue(DryBlockExaminer.wet(Material.SEAGRASS, null));
        assertFalse(DryBlockExaminer.wet(Material.AIR, null));
        assertFalse(DryBlockExaminer.wet(Material.SHORT_GRASS, null));
    }
}
