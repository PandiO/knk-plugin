package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** KNG-76: the trail's road cells - a road floor material with room above, at the trail's height or one off. */
class RoadSurfaceGroundTest {

    private final World world = mock(World.class);
    private final Map<String, Material> blocks = new HashMap<>();
    private RoadSurfaceGround ground;

    private void put(int x, int y, int z, Material m) {
        blocks.put(x + "," + y + "," + z, m);
    }

    @BeforeEach
    void setUp() {
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            Material m = blocks.getOrDefault(inv.getArgument(0) + "," + inv.getArgument(1) + "," + inv.getArgument(2), Material.AIR);
            Block b = mock(Block.class);
            when(b.getType()).thenReturn(m);
            when(b.isPassable()).thenReturn(m == Material.AIR);
            return b;
        });
        ground = new RoadSurfaceGround(world, Set.of("GRAVEL", "COBBLESTONE_SLAB"));
    }

    @Test
    void aRoadFloorWithRoomAboveAtTheTrailsHeightOrOneOff() {
        put(0, 64, 0, Material.GRAVEL);
        put(1, 65, 0, Material.COBBLESTONE_SLAB);
        put(2, 63, 0, Material.GRAVEL);
        put(3, 66, 0, Material.GRAVEL);

        assertEquals(OptionalInt.of(64), ground.roadFloor(0, 0, 64));
        assertEquals(OptionalInt.of(65), ground.roadFloor(1, 0, 64), "a step up");
        assertEquals(OptionalInt.of(63), ground.roadFloor(2, 0, 64), "a step down");
        assertTrue(ground.roadFloor(3, 0, 64).isEmpty(), "two up is a ledge");
        assertTrue(ground.stairOrSlab(1, 65, 0));
        assertFalse(ground.stairOrSlab(0, 64, 0));
    }

    @Test
    void grassBesideTheRoadOrACoveredFloorIsNoRoad() {
        put(0, 64, 0, Material.GRASS_BLOCK);
        put(1, 64, 0, Material.GRAVEL);
        put(1, 65, 0, Material.STONE);

        assertTrue(ground.roadFloor(0, 0, 64).isEmpty(), "not a road material");
        assertTrue(ground.roadFloor(1, 0, 64).isEmpty(), "no room above");
    }

    @Test
    void anUnloadedChunkIsNoRoad() {
        put(0, 64, 0, Material.GRAVEL);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);

        assertTrue(ground.roadFloor(0, 0, 64).isEmpty());
    }
}
