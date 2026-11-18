package net.knightsandkings.knk.paper.navigation.walk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.roads.walk.TerrainChangeListener;

/**
 * The paper side of the block-change hook (off-road navigation DESIGN § 5.2.3, § 11, decision O18): block place and
 * break, burning, fading and forming, explosions, piston moves and entities changing blocks (MONITOR, cancelled ones
 * ignored) mark their chunk; every {@link #FLUSH_TICKS} the batch goes to the core {@link TerrainChangeListener} once per
 * chunk, on the main thread. Unlike the road dirty tracker it does not ask whether the block is a road block: a wall
 * built across a courtyard shortcut matters as much. WorldEdit edits are not seen (the TTL is the backstop for them).
 * Registered only with {@code navigation.walk-links} on.
 */
public final class WalkTerrainListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(WalkTerrainListener.class.getName());
    /** A batch per second: links are dropped within a second of a change, and a burst of changes costs one call per chunk. */
    public static final long FLUSH_TICKS = 20L;

    /** A chunk of a world. */
    record ChunkRef(String world, int chunkX, int chunkZ) {
    }

    private final TerrainChangeListener target;
    private final Set<ChunkRef> dirty = new LinkedHashSet<>();
    private BukkitTask flushTimer;
    private long marked;
    private long flushed;

    public WalkTerrainListener(TerrainChangeListener target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    /** Registers the listener and starts the flush timer. */
    public void start(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        flushTimer = Bukkit.getScheduler().runTaskTimer(plugin, this::flush, FLUSH_TICKS, FLUSH_TICKS);
    }

    /** Stops the timer and unregisters; pending changes are dropped (the cache dies with the plugin). */
    public void stop() {
        if (flushTimer != null) {
            flushTimer.cancel();
            flushTimer = null;
        }
        HandlerList.unregisterAll(this);
        synchronized (dirty) {
            dirty.clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        mark(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        markAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        markAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        mark(event.getBlock());
        markAll(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        mark(event.getBlock());
        markAll(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        mark(event.getBlock());
    }

    private void markAll(List<Block> blocks) {
        for (Block block : blocks) {
            mark(block);
        }
    }

    private void mark(Block block) {
        if (block == null || block.getWorld() == null) {
            return;
        }
        mark(block.getWorld().getName(), block.getX(), block.getZ());
    }

    /** Marks the chunk of block column ({@code x}, {@code z}); any thread. */
    void mark(String world, int x, int z) {
        synchronized (dirty) {
            if (dirty.add(new ChunkRef(world, x >> 4, z >> 4))) {
                marked++;
            }
        }
    }

    /** Main thread timer: one call per marked chunk; a failing call is logged and the rest go on. */
    void flush() {
        List<ChunkRef> batch;
        synchronized (dirty) {
            if (dirty.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(dirty);
            dirty.clear();
        }
        for (ChunkRef chunk : batch) {
            try {
                target.terrainChanged(chunk.world(), chunk.chunkX(), chunk.chunkZ());
                flushed++;
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "[Navigation] Walk-link invalidation failed for chunk " + chunk, e);
            }
        }
    }

    /** Chunks waiting for the next flush. */
    int pendingCount() {
        synchronized (dirty) {
            return dirty.size();
        }
    }

    /** Chunks marked and chunks reported since the start (status). */
    public String describe() {
        return "block-change hook: " + marked + " chunks marked, " + flushed + " reported, " + pendingCount() + " waiting";
    }
}
