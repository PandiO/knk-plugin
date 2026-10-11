package net.knightsandkings.knk.paper.roads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.roads.RoadTile;
import net.knightsandkings.knk.core.ports.api.RoadNetworkCommandApi;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DESIGN §5.9 through the Bukkit listener: a palette block marks its tile, a non-palette block off the
 * road does not, a headroom block does, and the flush sends one markDirty per tile (failures re-marked).
 * Written for the developer's local build (paper-api is not resolvable in the cloud); the pure rules are
 * covered by DirtyTilesTest, which the scratch build runs.
 */
class RoadDirtyTrackerTest {
    private RoadNetworkCommandApi commandApi;
    private DirtyTiles dirty;
    private RoadDirtyTracker tracker;
    private World world;

    @BeforeEach
    void setUp() {
        commandApi = mock(RoadNetworkCommandApi.class);
        dirty = new DirtyTiles();
        dirty.setRoadMaterials(Set.of("STONE_BRICKS"));
        tracker = new RoadDirtyTracker(mock(Plugin.class), commandApi, dirty, () -> true);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
    }

    private Block block(Material material, int x, int y, int z) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getType()).thenReturn(material);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        return block;
    }

    @Test
    void paletteBlockMarksItsTileNonPaletteDoesNot() {
        tracker.onBreak(new BlockBreakEvent(block(Material.STONE_BRICKS, 700, 64, 20), mock(Player.class)));
        tracker.onBreak(new BlockBreakEvent(block(Material.OAK_LOG, 900, 64, 20), mock(Player.class)));

        assertEquals(Set.of(new TileKey("world", 1, 0)), dirty.pending());
    }

    @Test
    void headroomOfARoadCellMarksItsTile() {
        tracker.mark("world", "COBBLESTONE", 5, 65, 5); // no road cells known yet
        assertEquals(0, dirty.pendingCount());

        dirty.setRoadCells(new DirtyTiles.RoadCells(Set.of(net.knightsandkings.knk.core.util.BlockKey.pack(5, 64, 5))));
        tracker.mark("world", "COBBLESTONE", 5, 65, 5);
        assertEquals(Set.of(new TileKey("world", 0, 0)), dirty.pending());
    }

    @Test
    void flushSendsOneCallPerTileAndRemarksFailures() {
        RoadTile ok = new RoadTile(1, "world", 0, 0, 2, OffsetDateTime.now(), 1, true, 0, 0, 0, 0, List.of());
        when(commandApi.markDirty("world", 0, 0)).thenReturn(CompletableFuture.completedFuture(ok));
        when(commandApi.markDirty("world", 1, 0)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));
        dirty.mark("world", 10, 10);
        dirty.mark("world", 20, 10);
        dirty.mark("world", 600, 10);

        tracker.flushNow().join();

        verify(commandApi, times(1)).markDirty("world", 0, 0);
        verify(commandApi, times(1)).markDirty("world", 1, 0);
        assertEquals(Set.of(new TileKey("world", 1, 0)), dirty.pending(), "the failed tile waits for the next flush");
        assertTrue(dirty.pendingCount() == 1);
    }
}
