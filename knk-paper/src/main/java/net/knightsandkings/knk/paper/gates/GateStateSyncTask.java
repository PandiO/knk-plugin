package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.api.GateDoorsApi;
import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Keeps the physical (world) gate state and the DB-persisted gate state in sync, per
 * docs/features/gate-structure-animation/GATE_WORLD_SYNC_DESIGN.md:
 * <ul>
 *   <li>{@link #persistAllGateStates()} periodically (and once more on shutdown) pushes the
 *       current in-memory state of every gate back to the API, as a safety net for state changes
 *       that were never persisted (e.g. a crash mid-animation).</li>
 *   <li>{@link #logStartupSyncDiagnostics()} runs once at startup and only <b>diagnoses</b>
 *       mismatches between a gate's DB-loaded state and its physical world blocks (Mechanism A) -
 *       it never force-loads a chunk or force-places a block, so it costs nothing at scale.
 *       Correction is left to whichever of the other two mechanisms actually gets to a given
 *       gate first.</li>
 *   <li>{@link #checkAndFixGates(Collection)} is called by {@link DistrictGateLoader} right after
 *       a district's gates are (re)loaded (Mechanism B) - the primary, bounded correction path:
 *       force-loads exactly that district's gates' own chunks (via async chunk loading, no main
 *       thread hitch) and fixes anything found out of sync.</li>
 *   <li>A budgeted, round-robin periodic health-check (Mechanism C, started by {@link #start()})
 *       catches drift introduced after a gate was already synced (e.g. external world edits) by
 *       re-checking already-loaded gates only - it never forces a chunk load either, so its cost
 *       never grows with total gate count, only with how much of the map is currently active.</li>
 * </ul>
 */
public class GateStateSyncTask {
    private static final Logger LOGGER = Logger.getLogger(GateStateSyncTask.class.getName());

    private static final long DEFAULT_HEALTH_CHECK_INTERVAL_SECONDS = 300L;
    private static final int DEFAULT_HEALTH_CHECK_BATCH_SIZE = 15;

    private final GateManager gateManager;
    private final GateDoorsApi gateDoorsApi;
    private final Plugin plugin;
    private final long intervalTicks;
    private final Material fallbackMaterial;
    // Mechanism 1 kill switch, same flag GateAnimationTask uses - see ROTATION_GAP_FILL_DESIGN.md,
    // Decision 5. Needed here too: fix() force-places a gate's resting frame the same way an
    // animation completion does, so a diagonal-hinge gate gets the correct rasterized/paired
    // footprint too, not the old sparse one.
    private final boolean rasterizationEnabled;
    private final long healthCheckIntervalTicks;
    private final int healthCheckBatchSize;

    private BukkitTask task;
    private BukkitTask healthCheckTask;
    // Round-robin cursor into the (sorted-by-id, for a stable order) gate id list - see
    // GATE_WORLD_SYNC_DESIGN.md Mechanism C. Advances by healthCheckBatchSize each run, wrapping
    // around, so every currently-cached gate eventually gets checked without ever scanning the
    // full list in one run.
    private int healthCheckCursor = 0;

    public GateStateSyncTask(GateManager gateManager, GateDoorsApi gateDoorsApi, Plugin plugin,
                              long intervalSeconds, Material fallbackMaterial) {
        this(gateManager, gateDoorsApi, plugin, intervalSeconds, fallbackMaterial, true);
    }

    public GateStateSyncTask(GateManager gateManager, GateDoorsApi gateDoorsApi, Plugin plugin,
                              long intervalSeconds, Material fallbackMaterial, boolean rasterizationEnabled) {
        this(gateManager, gateDoorsApi, plugin, intervalSeconds, fallbackMaterial, rasterizationEnabled,
            DEFAULT_HEALTH_CHECK_INTERVAL_SECONDS, DEFAULT_HEALTH_CHECK_BATCH_SIZE);
    }

    public GateStateSyncTask(GateManager gateManager, GateDoorsApi gateDoorsApi, Plugin plugin,
                              long intervalSeconds, Material fallbackMaterial, boolean rasterizationEnabled,
                              long healthCheckIntervalSeconds, int healthCheckBatchSize) {
        this.gateManager = gateManager;
        this.gateDoorsApi = gateDoorsApi;
        this.plugin = plugin;
        this.intervalTicks = Math.max(1L, intervalSeconds) * 20L;
        this.fallbackMaterial = fallbackMaterial != null ? fallbackMaterial : Material.STONE;
        this.rasterizationEnabled = rasterizationEnabled;
        this.healthCheckIntervalTicks = Math.max(1L, healthCheckIntervalSeconds) * 20L;
        this.healthCheckBatchSize = Math.max(1, healthCheckBatchSize);
    }

    /**
     * Start the periodic DB persistence timer and the periodic world-sync health-check (Mechanism C).
     */
    public void start() {
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
            plugin, this::persistAllGateStates, intervalTicks, intervalTicks
        );
        LOGGER.info("GateStateSyncTask started (interval=" + (intervalTicks / 20L) + "s)");

        // Main thread, not async: check/fix touches Bukkit block state directly, and this is
        // cheap by construction (bounded batch, never forces a chunk load) - see runHealthCheckBatch.
        healthCheckTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin, this::runHealthCheckBatch, healthCheckIntervalTicks, healthCheckIntervalTicks
        );
        LOGGER.info("[GateWorldSync] Periodic health-check started (interval="
            + (healthCheckIntervalTicks / 20L) + "s, batchSize=" + healthCheckBatchSize + ")");
    }

    /**
     * Stop the periodic DB persistence timer and the periodic world-sync health-check.
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (healthCheckTask != null) {
            healthCheckTask.cancel();
            healthCheckTask = null;
        }
    }

    /**
     * Push the current in-memory state of every cached gate to the API.
     * Safe to call from any thread; blocks on each API call to guarantee completion (used on shutdown).
     */
    public void persistAllGateStates() {
        for (CachedGateDoor gate : gateManager.getAllGates().values()) {
            persistGateState(gate);
        }
    }

    private void persistGateState(CachedGateDoor gate) {
        if (gateDoorsApi == null || gate == null) {
            return;
        }

        // OPENING/CLOSING is transient; persist the state the animation is heading towards.
        AnimationState persistedState = gate.getCurrentState() == AnimationState.OPENING
            ? AnimationState.OPEN : gate.getCurrentState();
        String openedState = GateDoorOpenStateMapper.toWireValue(persistedState, gate.isJammed());

        try {
            gateDoorsApi.updateState(gate.getId(), openedState, gate.isDestroyed()).join();
            LOGGER.fine("Gate state synced to API: " + gate.getName() +
                " (openedState=" + openedState + ", destroyed=" + gate.isDestroyed() + ")");
        } catch (Exception e) {
            LOGGER.warning("Failed to sync gate state for '" + gate.getName() + "': " + e.getMessage());
        }

        try {
            gateDoorsApi.updateHealth(gate.getId(), gate.getHealthCurrent()).join();
            LOGGER.fine("Gate health synced to API: " + gate.getName() + " (health=" + gate.getHealthCurrent() + ")");
        } catch (Exception e) {
            LOGGER.warning("Failed to sync gate health for '" + gate.getName() + "': " + e.getMessage());
        }
    }

    // === Mechanism A: startup diagnostics only (never forces a chunk load or a block write) ===

    /**
     * Logs any gate whose physical world blocks don't match its DB-loaded state, for whichever
     * gates happen to already sit in a loaded chunk at boot (typically spawn-adjacent) - does
     * NOT force a chunk load or write any block. Correction is left entirely to
     * {@link #checkAndFixGates} (district-load, Mechanism B) or the periodic health-check
     * (Mechanism C). Must run on the main server thread (reads Bukkit block state).
     */
    public void logStartupSyncDiagnostics() {
        int verified = 0;
        int mismatched = 0;
        int skipped = 0;

        for (CachedGateDoor gate : gateManager.getAllGates().values()) {
            if (!isEligibleForSync(gate)) {
                continue;
            }

            World world = resolveWorld(gate);
            if (world == null) {
                skipped++;
                continue;
            }

            GateWorldSyncChecker.SyncResult result = GateWorldSyncChecker.check(world, gate, fallbackMaterial, rasterizationEnabled);
            if (result.totalCellCount() == 0 || result.fullyUnchecked()) {
                skipped++;
                continue;
            }

            verified++;
            if (!result.inSync()) {
                mismatched++;
                LOGGER.warning("[GateWorldSync] Gate '" + gate.getName() + "' (ID: " + gate.getId()
                    + ") is out of sync with the world: " + result.mismatchedCellCount() + "/" + result.totalCellCount()
                    + " block(s) do not match its " + gate.getCurrentState() + " state. Will be corrected the next "
                    + "time its district is entered.");
            }
        }

        LOGGER.info("[GateWorldSync] Startup sync check: " + verified + " gate(s) verified, " + mismatched
            + " mismatch(es) found (see warnings above), " + skipped + " gate(s) skipped (chunk not loaded - "
            + "will be checked when their district is next entered).");
    }

    // === Mechanism B: district-load check-and-fix (the primary correction path) ===

    /**
     * Called by {@link DistrictGateLoader} right after a district's gates finish (re)loading:
     * force-loads each eligible gate's own chunk(s) (async - no main thread hitch) and fixes
     * anything found out of sync. Bounded, predictable cost: proportional to however many
     * distinct chunks this specific batch of gates occupies, not the server's total gate count.
     * Safe to call from any thread; internally hops onto the main thread before touching Bukkit.
     */
    public void checkAndFixGates(Collection<Integer> gateIds) {
        if (gateIds == null || gateIds.isEmpty()) {
            return;
        }

        List<Integer> idsSnapshot = new ArrayList<>(gateIds);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (int gateId : idsSnapshot) {
                checkAndFixGate(gateId);
            }
        });
    }

    /** Must run on the main server thread (starts an async chunk load, then hops back). */
    private void checkAndFixGate(int gateId) {
        CachedGateDoor gate = gateManager.getGate(gateId);
        if (!isEligibleForSync(gate)) {
            return;
        }

        World world = resolveWorld(gate);
        if (world == null) {
            return;
        }

        List<GateRestingFramePlacer.RestingCell> cells =
            GateRestingFramePlacer.restingFrameCells(gate, GateWorldSyncChecker.restingFrame(gate), rasterizationEnabled);
        if (cells.isEmpty()) {
            return;
        }

        Set<Long> chunkKeys = new HashSet<>();
        List<CompletableFuture<Chunk>> chunkFutures = new ArrayList<>();
        for (GateRestingFramePlacer.RestingCell cell : cells) {
            int chunkX = cell.position().getBlockX() >> 4;
            int chunkZ = cell.position().getBlockZ() >> 4;
            long key = (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
            if (chunkKeys.add(key)) {
                chunkFutures.add(world.getChunkAtAsync(chunkX, chunkZ));
            }
        }

        CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0])).whenComplete((unused, error) -> {
            if (error != null) {
                LOGGER.warning("[GateWorldSync] Failed to load chunk(s) for gate '" + gate.getName() + "' (ID: "
                    + gate.getId() + "): " + error.getMessage());
                return;
            }
            // getChunkAtAsync's completion thread isn't contractually guaranteed to be the main
            // thread across Paper versions - hop explicitly before touching block state.
            plugin.getServer().getScheduler().runTask(plugin, () -> checkAndFixLoadedGate(gate, world));
        });
    }

    private void checkAndFixLoadedGate(CachedGateDoor gate, World world) {
        GateWorldSyncChecker.SyncResult result = GateWorldSyncChecker.check(world, gate, fallbackMaterial, rasterizationEnabled);
        if (result.inSync()) {
            LOGGER.fine("[GateWorldSync] Gate '" + gate.getName() + "' (ID: " + gate.getId() + ") already in sync.");
            return;
        }

        GateWorldSyncChecker.fix(world, gate, fallbackMaterial, rasterizationEnabled);
        LOGGER.info("[GateWorldSync] Corrected gate '" + gate.getName() + "' (ID: " + gate.getId() + "): "
            + result.mismatchedCellCount() + "/" + result.totalCellCount() + " block(s) did not match its "
            + gate.getCurrentState() + " state.");
    }

    // === Mechanism C: periodic health-check (ongoing drift detection, already-loaded gates only) ===

    /**
     * Round-robins a small budgeted batch of already-cached gates each run, checking (and fixing)
     * only those currently sitting in an already-loaded chunk - never forces a chunk load, so
     * this never grows unbounded as total gate count grows; cost only tracks how much of the map
     * currently has players near gates. Runs on the main thread (see {@link #start()}).
     */
    private void runHealthCheckBatch() {
        List<Integer> gateIds = new ArrayList<>(gateManager.getAllGates().keySet());
        if (gateIds.isEmpty()) {
            return;
        }
        Collections.sort(gateIds);

        int[] batchIndices = selectRoundRobinBatch(gateIds.size(), healthCheckCursor, healthCheckBatchSize);
        for (int index : batchIndices) {
            checkAndFixGateIfAlreadyLoaded(gateIds.get(index));
        }
        if (batchIndices.length > 0) {
            healthCheckCursor = (batchIndices[batchIndices.length - 1] + 1) % gateIds.size();
        }
    }

    /**
     * Pure round-robin batch selection: given a total item count, the current cursor, and the
     * desired batch size, returns the indices (into whatever externally-sorted list the caller
     * is walking) this batch should process, wrapping around the end of the list. No Bukkit/
     * GateManager coupling, so directly unit-testable - see GateStateSyncTaskRoundRobinTest.
     */
    static int[] selectRoundRobinBatch(int totalCount, int cursor, int batchSize) {
        if (totalCount <= 0 || batchSize <= 0) {
            return new int[0];
        }

        int normalizedCursor = cursor % totalCount;
        if (normalizedCursor < 0) {
            normalizedCursor += totalCount;
        }

        int size = Math.min(batchSize, totalCount);
        int[] indices = new int[size];
        for (int i = 0; i < size; i++) {
            indices[i] = (normalizedCursor + i) % totalCount;
        }
        return indices;
    }

    private void checkAndFixGateIfAlreadyLoaded(int gateId) {
        CachedGateDoor gate = gateManager.getGate(gateId);
        if (!isEligibleForSync(gate)) {
            return;
        }

        World world = resolveWorld(gate);
        if (world == null) {
            return;
        }

        GateWorldSyncChecker.SyncResult result = GateWorldSyncChecker.check(world, gate, fallbackMaterial, rasterizationEnabled);
        if (result.totalCellCount() == 0 || result.fullyUnchecked() || result.inSync()) {
            return;
        }

        GateWorldSyncChecker.fix(world, gate, fallbackMaterial, rasterizationEnabled);
        LOGGER.warning("[GateWorldSync] Periodic health-check corrected gate '" + gate.getName() + "' (ID: "
            + gate.getId() + "): " + result.mismatchedCellCount() + "/" + result.totalCellCount()
            + " block(s) had drifted from its " + gate.getCurrentState() + " state.");
    }

    // === Shared helpers ===

    private static boolean isEligibleForSync(CachedGateDoor gate) {
        if (gate == null || gate.isDestroyed() || gate.getBlocks().isEmpty()) {
            return false;
        }
        AnimationState state = gate.getCurrentState();
        return state == AnimationState.OPEN || state == AnimationState.CLOSED;
    }

    private static World resolveWorld(CachedGateDoor gate) {
        if (gate.getWorldName() == null || gate.getWorldName().isBlank()) {
            return null;
        }
        return Bukkit.getWorld(gate.getWorldName());
    }
}
