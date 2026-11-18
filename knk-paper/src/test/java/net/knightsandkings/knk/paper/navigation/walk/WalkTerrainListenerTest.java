package net.knightsandkings.knk.paper.navigation.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Off-road phase 2 (#705, DESIGN § 5.2.3, O18): the block-change hook on a MockBukkit server - block events mark their
 * chunk, the batch reaches the core listener once per chunk within a second, cancelled events and an unregistered hook
 * report nothing.
 */
class WalkTerrainListenerTest {

    private ServerMock server;
    private Plugin plugin;
    private WorldMock world;
    private PlayerMock player;
    private final List<String> reported = new ArrayList<>();
    private WalkTerrainListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("KnightsAndKings");
        world = server.addSimpleWorld("keep");
        player = server.addPlayer();
        listener = new WalkTerrainListener((w, cx, cz) -> reported.add(w + " " + cx + " " + cz));
        listener.start(plugin);
    }

    @AfterEach
    void tearDown() {
        listener.stop();
        MockBukkit.unmock();
    }

    private void breakAt(int x, int y, int z, boolean cancelled) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.STONE);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        event.setCancelled(cancelled);
        server.getPluginManager().callEvent(event);
    }

    @Test
    void blockChangesReachTheListenerOncePerChunkWithinASecond() {
        breakAt(5, 64, 5, false);
        breakAt(10, 65, 12, false); // same chunk (0, 0)
        breakAt(-1, 64, 40, false); // chunk (-1, 2)
        Block placed = world.getBlockAt(33, 64, 0);
        server.getPluginManager().callEvent(new BlockPlaceEvent(placed, placed.getState(), world.getBlockAt(33, 63, 0),
            new ItemStack(Material.STONE), player, true, EquipmentSlot.HAND));
        assertTrue(reported.isEmpty(), "batched");
        server.getScheduler().performTicks(WalkTerrainListener.FLUSH_TICKS);
        assertEquals(List.of("keep 0 0", "keep -1 2", "keep 2 0"), reported);
        server.getScheduler().performTicks(WalkTerrainListener.FLUSH_TICKS);
        assertEquals(3, reported.size(), "each change once");
        assertTrue(listener.describe().startsWith("block-change hook: 3 chunks marked, 3 reported"), listener.describe());
    }

    @Test
    void explosionsMarkEveryBlocksChunk() {
        Block centre = world.getBlockAt(0, 64, 0);
        List<Block> blocks = new ArrayList<>(List.of(world.getBlockAt(1, 64, 1), world.getBlockAt(-3, 64, -3)));
        server.getPluginManager().callEvent(new BlockExplodeEvent(centre, centre.getState(), blocks, 1f, null));
        server.getScheduler().performTicks(WalkTerrainListener.FLUSH_TICKS);
        assertEquals(List.of("keep 0 0", "keep -1 -1"), reported);
    }

    @Test
    void cancelledEventsAndAStoppedHookReportNothing() {
        breakAt(5, 64, 5, true);
        server.getScheduler().performTicks(WalkTerrainListener.FLUSH_TICKS);
        assertTrue(reported.isEmpty(), "a cancelled break changed nothing");
        listener.stop();
        breakAt(5, 64, 5, false);
        server.getScheduler().performTicks(WalkTerrainListener.FLUSH_TICKS * 2);
        assertTrue(reported.isEmpty(), "unregistered");
    }

    @Test
    void aFailingListenerDoesNotStopTheRest() {
        listener.stop();
        List<String> seen = new ArrayList<>();
        listener = new WalkTerrainListener((w, cx, cz) -> {
            seen.add(cx + " " + cz);
            if (cx == 0) {
                throw new IllegalStateException("boom");
            }
        });
        listener.start(plugin);
        breakAt(1, 64, 1, false);
        breakAt(17, 64, 1, false);
        server.getScheduler().performTicks(WalkTerrainListener.FLUSH_TICKS);
        assertEquals(List.of("0 0", "1 0"), seen);
    }
}
