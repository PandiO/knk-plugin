package net.knightsandkings.knk.paper.roads;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.block.BlockStateHolder;

import net.knightsandkings.knk.core.ports.api.RoadNetworkCommandApi;

/**
 * Marks road tiles dirty when the world changes under them (DESIGN §5.9; plan Phase 3
 * "RoadDirtyTracker"). Block place/break, explosions and piston moves (MONITOR, cancelled ones ignored)
 * go through {@link DirtyTiles#matters}: a profile material, or a road span / its headroom in the current
 * snapshot. WorldEdit edits mark the tile of every changed block: an {@link EditSessionEvent} at
 * {@code BEFORE_CHANGE} wraps the session's extent so {@code setBlock} records tiles (that may run off the
 * main thread under FAWE - {@link DirtyTiles} is thread-safe). Every 30 s the batch is flushed with one
 * {@code markDirty} per tile; a failed call is re-marked for the next flush.
 */
public final class RoadDirtyTracker implements Listener {
    private static final Logger LOGGER = Logger.getLogger(RoadDirtyTracker.class.getName());
    public static final long FLUSH_TICKS = 30 * 20L;

    private final Plugin plugin;
    private final RoadNetworkCommandApi commandApi;
    private final DirtyTiles dirty;
    private final Supplier<Boolean> apiReachable;
    private BukkitTask flushTimer;
    private WorldEditHook worldEditHook;

    /**
     * @param apiReachable answers whether a synchronous shutdown flush may be attempted (null = always try)
     */
    public RoadDirtyTracker(Plugin plugin, RoadNetworkCommandApi commandApi, DirtyTiles dirty, Supplier<Boolean> apiReachable) {
        this.plugin = plugin;
        this.commandApi = Objects.requireNonNull(commandApi, "commandApi");
        this.dirty = Objects.requireNonNull(dirty, "dirty");
        this.apiReachable = apiReachable == null ? () -> true : apiReachable;
    }

    public DirtyTiles dirtyTiles() {
        return dirty;
    }

    // ===== lifecycle =====

    /** Registers the Bukkit listener and the WorldEdit hook and starts the 30 s flush. */
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        try {
            worldEditHook = new WorldEditHook();
            WorldEdit.getInstance().getEventBus().register(worldEditHook);
        } catch (RuntimeException | LinkageError e) {
            worldEditHook = null;
            LOGGER.log(Level.WARNING, "[Roads] WorldEdit hook not installed; WorldEdit edits won't mark tiles dirty: " + e.getMessage());
        }
        flushTimer = Bukkit.getScheduler().runTaskTimer(plugin, this::flush, FLUSH_TICKS, FLUSH_TICKS);
    }

    /** Stops the timer and unhooks WorldEdit; a last flush is attempted synchronously when the API is reachable. */
    public void stop() {
        if (flushTimer != null) {
            flushTimer.cancel();
            flushTimer = null;
        }
        if (worldEditHook != null) {
            try {
                WorldEdit.getInstance().getEventBus().unregister(worldEditHook);
            } catch (RuntimeException ignored) {
                // WorldEdit already gone
            }
            worldEditHook = null;
        }
        if (dirty.pendingCount() > 0 && Boolean.TRUE.equals(apiReachable.get())) {
            try {
                flushNow().get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.warning("[Roads] Shutdown dirty flush incomplete (" + dirty.pendingCount() + " tile(s) not marked): " + e.getMessage());
            }
        }
    }

    // ===== Bukkit events =====

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        Block replaced = event.getBlockReplacedState().getBlock();
        // The material that appears (a road block placed) or the spot (headroom filled)
        mark(placed.getWorld().getName(), placed.getType().name(), placed.getX(), placed.getY(), placed.getZ());
        if (replaced != placed) {
            mark(replaced.getWorld().getName(), event.getBlockReplacedState().getType().name(), replaced.getX(), replaced.getY(), replaced.getZ());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        mark(block.getWorld().getName(), block.getType().name(), block.getX(), block.getY(), block.getZ());
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
        markAll(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        markAll(event.getBlocks());
    }

    private void markAll(List<Block> blocks) {
        for (Block block : blocks) {
            mark(block.getWorld().getName(), block.getType().name(), block.getX(), block.getY(), block.getZ());
        }
    }

    /** Main thread or not; the pure check decides. */
    void mark(String world, String material, int x, int y, int z) {
        if (dirty.markIfMatters(world, material, x, y, z)) {
            LOGGER.fine("[Roads] Tile " + TileKey.of(world, x, z) + " dirty: " + material + " at " + x + "," + y + "," + z);
        }
    }

    // ===== WorldEdit =====

    /** Registered on WorldEdit's event bus; wraps each edit session's extent at BEFORE_CHANGE. */
    public final class WorldEditHook {
        @Subscribe
        public void onEditSession(EditSessionEvent event) {
            if (event.getStage() != EditSession.Stage.BEFORE_CHANGE || event.getWorld() == null) {
                return;
            }
            String world = event.getWorld().getName();
            event.setExtent(new AbstractDelegateExtent(event.getExtent()) {
                @Override
                public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 position, T block) throws WorldEditException {
                    dirty.mark(world, position.getBlockX(), position.getBlockZ());
                    return super.setBlock(position, block);
                }
            });
        }
    }

    // ===== flush =====

    /** Main thread timer: sends one markDirty per pending tile; failures go back into the batch. */
    void flush() {
        if (dirty.pendingCount() == 0) {
            return;
        }
        flushNow();
    }

    /** Sends the whole batch now; the future completes when every call has answered (never fails). */
    public CompletableFuture<Void> flushNow() {
        List<TileKey> batch = dirty.drain();
        if (batch.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        LOGGER.info("[Roads] Marking " + batch.size() + " tile(s) dirty: " + batch);
        List<CompletableFuture<Void>> calls = batch.stream()
            .map(key -> commandApi.markDirty(key.world(), key.tileX(), key.tileZ())
                .<Void>handle((tile, ex) -> {
                    if (ex != null) {
                        dirty.mark(key);
                        LOGGER.warning("[Roads] markDirty " + key + " failed, will retry: " + RoadMessages.describeError(ex));
                    }
                    return null;
                }))
            .toList();
        return CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new));
    }
}
