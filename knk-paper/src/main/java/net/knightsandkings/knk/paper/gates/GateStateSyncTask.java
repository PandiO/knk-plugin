package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGate;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.gates.GateSpatialIndex;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Keeps the physical (world) gate state and the DB-persisted gate state in sync in both
 * directions:
 * <ul>
 *   <li>{@link #reconcileWorldOnStartup()} runs once at startup and forces every gate's blocks
 *       into the position matching the state loaded from the API, so a gate left open in the
 *       world while the DB says closed (or vice versa) is corrected on the next boot.</li>
 *   <li>{@link #persistAllGateStates()} periodically (and once more on shutdown) pushes the
 *       current in-memory state of every gate back to the API, as a safety net for state changes
 *       that were never persisted (e.g. a crash mid-animation).</li>
 * </ul>
 */
public class GateStateSyncTask {
    private static final Logger LOGGER = Logger.getLogger(GateStateSyncTask.class.getName());

    private final GateManager gateManager;
    private final GateStructuresApi gateStructuresApi;
    private final Plugin plugin;
    private final long intervalTicks;
    private final Material fallbackMaterial;
    // Mechanism 1 kill switch, same flag GateAnimationTask uses - see ROTATION_GAP_FILL_DESIGN.md,
    // Decision 5. Needed here too: reconcileWorldOnStartup force-places a gate's resting frame
    // the same way an animation completion does, so a diagonal-hinge gate already OPEN when the
    // server starts gets the correct rasterized/paired footprint too, not the old sparse one.
    private final boolean rasterizationEnabled;

    private BukkitTask task;

    public GateStateSyncTask(GateManager gateManager, GateStructuresApi gateStructuresApi, Plugin plugin,
                              long intervalSeconds, Material fallbackMaterial) {
        this(gateManager, gateStructuresApi, plugin, intervalSeconds, fallbackMaterial, true);
    }

    public GateStateSyncTask(GateManager gateManager, GateStructuresApi gateStructuresApi, Plugin plugin,
                              long intervalSeconds, Material fallbackMaterial, boolean rasterizationEnabled) {
        this.gateManager = gateManager;
        this.gateStructuresApi = gateStructuresApi;
        this.plugin = plugin;
        this.intervalTicks = Math.max(1L, intervalSeconds) * 20L;
        this.fallbackMaterial = fallbackMaterial != null ? fallbackMaterial : Material.STONE;
        this.rasterizationEnabled = rasterizationEnabled;
    }

    /**
     * Start the periodic DB persistence timer.
     */
    public void start() {
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
            plugin, this::persistAllGateStates, intervalTicks, intervalTicks
        );
        LOGGER.info("GateStateSyncTask started (interval=" + (intervalTicks / 20L) + "s)");
    }

    /**
     * Stop the periodic DB persistence timer.
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * Push the current in-memory state of every cached gate to the API.
     * Safe to call from any thread; blocks on each API call to guarantee completion (used on shutdown).
     */
    public void persistAllGateStates() {
        for (CachedGate gate : gateManager.getAllGates().values()) {
            persistGateState(gate);
        }
    }

    private void persistGateState(CachedGate gate) {
        if (gateStructuresApi == null || gate == null) {
            return;
        }

        // OPENING/CLOSING is transient; persist the state the animation is heading towards.
        boolean isOpened = gate.getCurrentState() == AnimationState.OPEN
            || gate.getCurrentState() == AnimationState.OPENING;

        try {
            gateStructuresApi.updateGateState(gate.getId(), isOpened, gate.isDestroyed(), gate.isJammed()).join();
            LOGGER.fine("Gate state synced to API: " + gate.getName() +
                " (opened=" + isOpened + ", destroyed=" + gate.isDestroyed() + ", jammed=" + gate.isJammed() + ")");
        } catch (Exception e) {
            LOGGER.warning("Failed to sync gate state for '" + gate.getName() + "': " + e.getMessage());
        }

        try {
            gateStructuresApi.updateGateHealth(gate.getId(), gate.getHealthCurrent()).join();
            LOGGER.fine("Gate health synced to API: " + gate.getName() + " (health=" + gate.getHealthCurrent() + ")");
        } catch (Exception e) {
            LOGGER.warning("Failed to sync gate health for '" + gate.getName() + "': " + e.getMessage());
        }
    }

    /**
     * Force every gate's blocks into the position matching its loaded state (CLOSED or OPEN),
     * clearing any stale blocks left at the opposite end. Must run on the main server thread.
     */
    public void reconcileWorldOnStartup() {
        for (CachedGate gate : gateManager.getAllGates().values()) {
            reconcileGateWithWorld(gate);
        }
        LOGGER.info("Gate world reconciliation complete for " + gateManager.getAllGates().size() + " gate(s)");
    }

    private void reconcileGateWithWorld(CachedGate gate) {
        if (gate == null || gate.isDestroyed() || gate.getBlocks().isEmpty()) {
            return;
        }

        AnimationState state = gate.getCurrentState();
        if (state != AnimationState.OPEN && state != AnimationState.CLOSED) {
            // Only stable states are loaded at startup; skip anything unexpected.
            return;
        }

        World world = gate.getWorldName() != null && !gate.getWorldName().isBlank()
            ? Bukkit.getWorld(gate.getWorldName())
            : null;
        if (world == null) {
            LOGGER.warning("Cannot reconcile gate '" + gate.getName() + "': world '" + gate.getWorldName() + "' is not loaded");
            return;
        }

        int totalFrames = gate.getAnimationDurationTicks();
        int targetFrame = state == AnimationState.OPEN ? totalFrames : 0;
        int staleFrame = targetFrame == 0 ? totalFrames : 0;

        // Clear whatever the stale (opposite) resting frame would have occupied first, the same
        // way GateAnimationTask vacates before placing - using the exact stale footprint
        // (rasterized/paired where applicable) rather than the plain per-block one, so a
        // Mechanism-1-filled gap left over from a previous run is actually cleaned up too.
        Set<Long> targetCells = new HashSet<>();
        List<GateRestingFramePlacer.RestingCell> targetCellList =
            GateRestingFramePlacer.restingFrameCells(gate, targetFrame, rasterizationEnabled);
        for (GateRestingFramePlacer.RestingCell cell : targetCellList) {
            targetCells.add(GateSpatialIndex.packCell(cell.position()));
        }

        for (GateRestingFramePlacer.RestingCell staleCell : GateRestingFramePlacer.restingFrameCells(gate, staleFrame, rasterizationEnabled)) {
            if (targetCells.contains(GateSpatialIndex.packCell(staleCell.position()))) {
                continue;
            }
            GateBlockPlacer.removeBlockIfMatches(world, staleCell.position(), staleCell.blockData(), fallbackMaterial);
        }

        for (GateRestingFramePlacer.RestingCell targetCell : targetCellList) {
            GateBlockPlacer.placeBlock(world, targetCell.position(), targetCell.blockData(), fallbackMaterial);
        }
    }
}
