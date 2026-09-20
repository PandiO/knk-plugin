package net.knightsandkings.knk.paper.gates;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GateBlockPlacer, focused on item 7's snow-layer handling
 * (QOL_BUGFIX_BACKLOG.md #7): a snow layer resting on a gate block never gets its usual
 * "break when unsupported" check, because gate placement/removal always disables physics -
 * left unhandled it hovers in mid-air once the gate block beneath it moves away.
 */
class GateBlockPlacerTest {

    private static final Vector POSITION = new Vector(10, 64, 20);
    private static final Vector ABOVE = new Vector(10, 65, 20);
    private static final String BLOCK_DATA = "minecraft:oak_planks";
    private static final Material FALLBACK = Material.OAK_PLANKS;

    private World world;
    private MockedStatic<Bukkit> bukkitMock;

    @BeforeEach
    void setUp() {
        world = mock(World.class);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        // GateBlockPlacer.placeBlock/removeBlock look the block up via a Location, while
        // getBlockIfLoaded (used by the ...IfVacant/...IfMatches entry points and by this
        // test's own snow-layer assertions) looks it up by raw coordinates - redirect the
        // Location overload to whatever coordinate-based stub a test has set up, so both
        // paths return the same mock Block for the same position.
        when(world.getBlockAt(any(Location.class))).thenAnswer(invocation -> {
            Location loc = invocation.getArgument(0);
            return world.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        });

        BlockData planks = mock(BlockData.class);
        when(planks.getMaterial()).thenReturn(Material.OAK_PLANKS);
        when(planks.matches(any())).thenReturn(true);

        bukkitMock = mockStatic(Bukkit.class);
        bukkitMock.when(() -> Bukkit.createBlockData(anyString())).thenReturn(planks);
    }

    @AfterEach
    void tearDown() {
        bukkitMock.close();
    }

    private Block blockAt(Vector position, Material type) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(type);
        BlockData data = mock(BlockData.class);
        when(data.getMaterial()).thenReturn(type);
        when(data.matches(any())).thenReturn(false);
        when(block.getBlockData()).thenReturn(data);
        when(world.getBlockAt(position.getBlockX(), position.getBlockY(), position.getBlockZ()))
            .thenReturn(block);
        return block;
    }

    @Test
    void placeBlockIfVacant_clearsSnowLayerAbove_whenPlacingIntoVacantCell() {
        blockAt(POSITION, Material.AIR);
        Block above = blockAt(ABOVE, Material.SNOW);

        boolean placed = GateBlockPlacer.placeBlockIfVacant(world, POSITION, BLOCK_DATA, FALLBACK);

        assertTrue(placed);
        verify(above).setType(Material.AIR, false);
    }

    @Test
    void placeBlockIfVacant_clearsSnowLayerAbove_whenCellAlreadyHoldsTheExpectedGateBlock() {
        blockAt(POSITION, Material.OAK_PLANKS);
        Block above = blockAt(ABOVE, Material.SNOW);

        boolean placed = GateBlockPlacer.placeBlockIfVacant(world, POSITION, BLOCK_DATA, FALLBACK);

        assertTrue(placed);
        verify(above).setType(Material.AIR, false);
    }

    @Test
    void placeBlockIfVacant_leavesNonLayerBlockAboveUntouched() {
        blockAt(POSITION, Material.AIR);
        Block above = blockAt(ABOVE, Material.SNOW_BLOCK);

        GateBlockPlacer.placeBlockIfVacant(world, POSITION, BLOCK_DATA, FALLBACK);

        verify(above, never()).setType(any(), anyBoolean());
    }

    @Test
    void placeBlockIfVacant_skippedPlacement_doesNotClearSnowAbove() {
        blockAt(POSITION, Material.CHEST); // occupied by a real, non-replaceable player block
        Block above = blockAt(ABOVE, Material.SNOW);

        boolean placed = GateBlockPlacer.placeBlockIfVacant(world, POSITION, BLOCK_DATA, FALLBACK);

        assertFalse(placed);
        verify(above, never()).setType(any(), anyBoolean());
    }

    @Test
    void removeBlockIfMatches_clearsSnowLayerAbove_whenVacatingTheGatesOwnBlock() {
        Block target = blockAt(POSITION, Material.OAK_PLANKS);
        Block above = blockAt(ABOVE, Material.SNOW);

        boolean removed = GateBlockPlacer.removeBlockIfMatches(world, POSITION, BLOCK_DATA, FALLBACK);

        assertTrue(removed);
        verify(above).setType(Material.AIR, false);
        verify(target).setType(Material.AIR, false);
    }

    @Test
    void removeBlockIfMatches_skippedRemoval_doesNotClearSnowAbove() {
        blockAt(POSITION, Material.CHEST); // not the gate's own block - removal is refused
        Block above = blockAt(ABOVE, Material.SNOW);

        boolean removed = GateBlockPlacer.removeBlockIfMatches(world, POSITION, BLOCK_DATA, FALLBACK);

        assertFalse(removed);
        verify(above, never()).setType(any(), anyBoolean());
    }

    @Test
    void removeBlockIfMatches_noSnowAbove_doesNotTouchTheCellAbove() {
        Block target = blockAt(POSITION, Material.OAK_PLANKS);
        Block above = blockAt(ABOVE, Material.AIR);

        GateBlockPlacer.removeBlockIfMatches(world, POSITION, BLOCK_DATA, FALLBACK);

        verify(above, never()).setType(any(), anyBoolean());
        verify(target).setType(Material.AIR, false);
    }
}
