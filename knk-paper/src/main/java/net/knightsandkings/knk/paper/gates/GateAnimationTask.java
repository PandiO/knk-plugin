package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.api.GateDoorsApi;
import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateFrameCalculator;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.gates.GateSpatialIndex;
import net.knightsandkings.knk.paper.integration.WorldGuardIntegration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Bukkit runnable that handles gate animation on every server tick.
 * Iterates through all gates in OPENING or CLOSING state and updates their block positions.
 * Integrates with WorldGuard to sync regions when animation completes.
 */
public class GateAnimationTask extends BukkitRunnable {
    private static final Logger LOGGER = Logger.getLogger(GateAnimationTask.class.getName());
    
    // TPS threshold for lag detection
    private static final double LAG_TPS_THRESHOLD = 15.0;
    
    // Minimum time between lag checks (milliseconds)
    private static final long LAG_CHECK_INTERVAL = 1000;

    private static final double ENTITY_PUSH_RADIUS = 5.0;
    private static final int ENTITY_COLLISION_FRAMES_THRESHOLD = 2;

    // Consecutive ticks a door block must be blocked by a non-replaceable obstruction before
    // the gate is marked jammed - filters out a single transient block (e.g. a player mid-swing).
    private static final int JAM_THRESHOLD_TICKS = 5;
    private static final long MS_PER_TICK = 50L;

    // Bukkit's playSound has no separate playback-speed control - pitch is the only knob, and
    // Minecraft couples it directly to sample rate, so the lowest allowed pitch is also the
    // slowest/deepest the clip can play. Played once, on the tick the gate starts animating.
    private static final float GATE_SOUND_PITCH = 0.5f;

    private final GateManager gateManager;
    private final World world;
    private final Material fallbackMaterial;
    // Currently unused (item 6.2 removed this class's only calls - see finishOpening/
    // finishClosing) but kept wired rather than removed, since WorldGuardIntegration itself still
    // exists as a general-purpose collaborator; not deleted from the constructor to avoid churn
    // on a dependency this class may reasonably want again.
    private final WorldGuardIntegration worldGuardIntegration;
    private final GateDoorsApi gateDoorsApi;
    private final Plugin plugin;
    private final GateDisplayManager displayManager;
    // Mechanism 1 kill switch (Decision 5, ROTATION_GAP_FILL_DESIGN.md): default on, but lets an
    // admin fall back to today's exact sparse-lattice endpoint behavior server-wide in seconds,
    // without a code deploy, if a defect is ever found (it runs automatically, with zero admin
    // action, for every diagonal-hinge ROTATION gate).
    private final boolean rasterizationEnabled;

    private long lastLagCheck = 0;
    private boolean isLagging = false;
    private final Set<Integer> emptySnapshotWarnings = new HashSet<>();
    private final Map<Integer, AnimationState> lastObservedState = new HashMap<>();
    private final Map<Integer, Integer> jamTickCounters = new HashMap<>();

    /**
     * Create a new gate animation task with Mechanism 1 rasterization enabled by default.
     *
     * @param gateManager The gate manager containing all cached gates
     * @param world The world to place blocks in
     * @param fallbackMaterial Fallback material if block data is corrupted
     * @param worldGuardIntegration WorldGuard integration for region sync
     * @param gateDoorsApi API client used to persist state once an animation completes
     * @param plugin Plugin instance for scheduling the async persistence call
     * @param displayManager Manager used to refresh the gate's info display when its status changes
     */
    public GateAnimationTask(GateManager gateManager, World world, Material fallbackMaterial,
                             WorldGuardIntegration worldGuardIntegration, GateDoorsApi gateDoorsApi,
                             Plugin plugin, GateDisplayManager displayManager) {
        this(gateManager, world, fallbackMaterial, worldGuardIntegration, gateDoorsApi, plugin, displayManager, true);
    }

    /**
     * @param rasterizationEnabled Mechanism 1 kill switch - see {@code gates.rotationGapFill.rasterization-enabled}
     */
    public GateAnimationTask(GateManager gateManager, World world, Material fallbackMaterial,
                             WorldGuardIntegration worldGuardIntegration, GateDoorsApi gateDoorsApi,
                             Plugin plugin, GateDisplayManager displayManager, boolean rasterizationEnabled) {
        this.gateManager = gateManager;
        this.world = world;
        this.fallbackMaterial = fallbackMaterial != null ? fallbackMaterial : Material.STONE;
        this.worldGuardIntegration = worldGuardIntegration;
        this.gateDoorsApi = gateDoorsApi;
        this.plugin = plugin;
        this.displayManager = displayManager;
        this.rasterizationEnabled = rasterizationEnabled;
        LOGGER.info("[GateAnimation] Scheduled animation task for world '" + world.getName() + "'");
    }

    @Override
    public void run() {
        // Check for lag periodically
        checkServerLag();

        // Get all gates
        Map<Integer, CachedGateDoor> gates = gateManager.getAllGates();

        for (CachedGateDoor gate : gates.values()) {
            if (!gate.getWorldName().isBlank() && !gate.getWorldName().equals(world.getName())) {
                continue;
            }

            AnimationState state = gate.getCurrentState();

            AnimationState previousState = lastObservedState.put(gate.getId(), state);
            boolean justStartedAnimating = state != previousState
                && (state == AnimationState.OPENING || state == AnimationState.CLOSING);

            if (justStartedAnimating && "ROTATION".equals(gate.getMotionType())) {
                LOGGER.info("[GateAnimation] Gate '" + gate.getName() + "' (ID: " + gate.getId() + ") starting "
                    + state + " rotation: hingeAxis=" + gate.getHingeAxis() + ", uAxis=" + gate.getUAxis()
                    + ", vAxis=" + gate.getVAxis() + ", nAxis=" + gate.getNAxis()
                    + ", rotationMaxAngleDegrees=" + gate.getRotationMaxAngleDegrees()
                    + ", clipToGeometryBounds=" + gate.isClipToGeometryBounds()
                    + " (W=" + gate.getGeometryWidth() + ", H=" + gate.getGeometryHeight()
                    + ", D=" + gate.getGeometryDepth() + ")");
            }

            // Only process gates that are animating
            if (state != AnimationState.OPENING && state != AnimationState.CLOSING) {
                emptySnapshotWarnings.remove(gate.getId());
                jamTickCounters.remove(gate.getId());
                continue;
            }

            // While jammed, hold the animation clock still: shift the start time forward by
            // one tick so the elapsed-time-based frame below stays pinned, instead of skipping
            // ahead once the obstruction clears. Placement is still retried below every tick.
            if (gate.isJammed()) {
                gate.setAnimationStartTime(gate.getAnimationStartTime() + MS_PER_TICK);
            }

            // Skip if gate is inactive or destroyed (decision 5.0-B: a structure-level override
            // takes effect immediately, so this checks the effective value, not the door's own).
            if (!gate.isEffectivelyActive() || gate.isEffectivelyDestroyed()) {
                LOGGER.warning("[GateAnimation] Skipping gate '" + gate.getName() + "' (ID: " + gate.getId()
                    + ") because it is " + (!gate.isEffectivelyActive() ? "inactive" : "destroyed") + ".");
                continue;
            }

            if (gate.getBlocks().isEmpty()) {
                if (emptySnapshotWarnings.add(gate.getId())) {
                    LOGGER.warning("[GateAnimation] Gate '" + gate.getName() + "' (ID: " + gate.getId()
                        + ") has no block snapshots. State will complete but no blocks can be animated.");
                }
            }

            // Calculate current frame based on elapsed time
            long currentTime = System.currentTimeMillis();
            long elapsedTicks = (currentTime - gate.getAnimationStartTime()) / 50; // 50ms per tick
            int currentFrame = (int) elapsedTicks;

            // Clamp frame to valid range
            int totalFrames = gate.getAnimationDurationTicks();
            
            if (state == AnimationState.CLOSING) {
                // Closing: count down from totalFrames to 0
                currentFrame = totalFrames - currentFrame;
                currentFrame = Math.max(0, Math.min(currentFrame, totalFrames));
            } else {
                // Opening: count up from 0 to totalFrames
                currentFrame = Math.max(0, Math.min(currentFrame, totalFrames));
            }

            // Update gate's current frame
            gate.setCurrentFrame(currentFrame);

            playGateSoundIfDue(gate, state, justStartedAnimating);

            if (currentFrame == 0 || currentFrame == totalFrames || currentFrame % 20 == 0) {
                LOGGER.info("[GateAnimation] Gate '" + gate.getName() + "' (ID: " + gate.getId() + ") "
                    + state + " frame " + currentFrame + "/" + totalFrames + " in world '" + world.getName() + "'.");
            }

            // Check if animation should update this frame (always retry every tick while
            // jammed, regardless of AnimationTickRate, so an obstruction clearing is noticed
            // promptly rather than waiting for the next tick-rate-aligned frame).
            if (!gate.isJammed() && !GateFrameCalculator.shouldUpdateFrame(gate, currentFrame)) {
                continue;
            }

            // If lagging, skip to final position
            if (isLagging && currentFrame > totalFrames / 2) {
                LOGGER.fine("Server lagging, skipping to final position for gate: " + gate.getName());
                currentFrame = state == AnimationState.OPENING ? totalFrames : 0;
                gate.setCurrentFrame(currentFrame);
            }

            // Handle entity push before updating blocks
            handleEntityPush(gate, currentFrame);

            // Update all block positions for this frame
            updateGateBlocks(gate, currentFrame);

            // Check if animation is complete
            if (currentFrame >= totalFrames && state == AnimationState.OPENING) {
                finishOpening(gate);
            } else if (currentFrame <= 0 && state == AnimationState.CLOSING) {
                finishClosing(gate);
            }
        }
    }

    /**
     * Update all block positions for a gate at a specific frame.
     * 
     * @param gate The gate to update
     * @param frame The current animation frame
     */
    private void updateGateBlocks(CachedGateDoor gate, int frame) {
        if (gate == null || gate.getBlocks() == null) {
            return;
        }

        int previousFrame = gate.getCurrentState() == AnimationState.OPENING
            ? Math.max(0, frame - Math.max(1, gate.getAnimationTickRate()))
            : Math.min(gate.getAnimationDurationTicks(), frame + Math.max(1, gate.getAnimationTickRate()));

        // Keep each rotating block's own facing/axis blockstate in sync with how far the door
        // has swung, not just its position - a no-op (returns the original angle 0) for
        // non-ROTATION gates. Vacancies use the previous frame's angle so the removed blockdata
        // still matches whatever was actually placed there last.
        double currentAngle = GateFrameCalculator.calculateRotationAngle(gate, frame);
        double previousAngle = GateFrameCalculator.calculateRotationAngle(gate, previousFrame);

        List<BlockPlacement> placements = new ArrayList<>();
        List<BlockPlacement> vacancies = new ArrayList<>();
        List<BlockMove> moves = new ArrayList<>();
        Set<Long> targetCells = new HashSet<>();

        // Blocks whose calculated position fell outside the gate's geometry box this frame
        // (calculateBlockPosition returned null because isWithinGeometryBounds rejected it) -
        // these are otherwise dropped completely silently, with no other signal that they
        // were ever supposed to be placed.
        int clippedPlacementCount = 0;
        int clippedVacancyCount = 0;

        for (BlockSnapshot block : gate.getBlocks()) {
            if (block == null) {
                continue;
            }

            // Mechanism 2 (ROTATION_GAP_FILL_DESIGN.md) position blending: a block paired with a
            // manually-scanned open state converges toward that scan's real position as the door
            // swings (looked up internally by calculateBlockPosition) - unaffected by the fix
            // below, which is about ORIENTATION only.
            Vector worldPos = GateFrameCalculator.calculateBlockPosition(gate, block, frame);
            Vector previousPosition = GateFrameCalculator.calculateBlockPosition(gate, block, previousFrame);

            // Orientation during the swing always uses the same angle-based procedural rotation
            // as an unpaired block, regardless of pairing (fixed 2026-09-15, live-tested with a
            // dense REGION-mode open scan): there's no real scanned orientation for the
            // in-between angles a paired block passes through mid-swing anyway, so hard-cutting
            // to the paired open block's orientation at every frame - including frame 0, before
            // any rotation has happened - showed the door's true CLOSED-state blocks in their
            // OPEN-state orientation the instant the animation started. The paired block's real
            // scanned orientation is still used, correctly, once the door reaches its true open
            // resting frame - see GateRestingFramePlacer.restingFrameCells, which the completed
            // animation's finishOpening/finishClosing resync onto regardless of what this
            // per-tick swing placed.
            if (worldPos != null) {
                if (!GateBlockPlacer.isChunkLoaded(world, worldPos)) {
                    LOGGER.fine("Gate " + gate.getName() + " is in unloaded chunk, pausing animation");
                    return;
                }
                String orientedBlockData = GateBlockOrientation.applyRotation(block.getBlockData(), gate, currentAngle);
                placements.add(new BlockPlacement(worldPos, orientedBlockData));
                targetCells.add(GateSpatialIndex.packCell(worldPos));
            } else if (gate.isClipToGeometryBounds()) {
                clippedPlacementCount++;
            }

            if (previousPosition != null) {
                String orientedVacancyData = GateBlockOrientation.applyRotation(block.getBlockData(), gate, previousAngle);
                vacancies.add(new BlockPlacement(previousPosition, orientedVacancyData));
            } else if (gate.isClipToGeometryBounds()) {
                clippedVacancyCount++;
            }

            moves.add(new BlockMove(previousPosition, worldPos));
        }

        boolean shouldLogThisFrame = frame == 0 || frame == gate.getAnimationDurationTicks() || frame % 20 == 0;
        if (shouldLogThisFrame && (clippedPlacementCount > 0 || clippedVacancyCount > 0)) {
            LOGGER.warning("[GateAnimation] Gate '" + gate.getName() + "' (ID: " + gate.getId() + ") frame " + frame
                + ": " + clippedPlacementCount + "/" + gate.getBlocks().size()
                + " target position(s) and " + clippedVacancyCount + "/" + gate.getBlocks().size()
                + " vacancy position(s) fell outside GeometryWidth/Height/Depth bounds and were skipped.");
        }

        // Clear vacated cells first, but never a cell another block moves into this frame:
        // otherwise a later snapshot erases what an earlier one just placed.
        for (BlockPlacement vacancy : vacancies) {
            if (targetCells.contains(GateSpatialIndex.packCell(vacancy.position()))) {
                continue;
            }
            GateBlockPlacer.removeBlockIfMatches(world, vacancy.position(), vacancy.blockData(), fallbackMaterial);
        }

        int blockedCount = 0;
        for (BlockPlacement placement : placements) {
            if (!GateBlockPlacer.placeBlockIfVacant(world, placement.position(), placement.blockData(), fallbackMaterial)) {
                blockedCount++;
            }
        }

        if (shouldLogThisFrame && blockedCount > 0) {
            LOGGER.warning("[GateAnimation] Gate '" + gate.getName() + "' (ID: " + gate.getId() + ") frame " + frame
                + ": " + blockedCount + "/" + placements.size()
                + " placement(s) blocked by an existing, non-matching block this frame.");
        }

        // Keep the spatial index in lockstep with the block mutations above, so a hit-detection
        // lookup can never observe a cell that disagrees with the real world block.
        GateSpatialIndex spatialIndex = gateManager.getSpatialIndex();
        for (BlockMove move : moves) {
            spatialIndex.move(gate.getWorldName(), move.previousPosition(), move.worldPos(), gate.getId());
        }

        handleJamTracking(gate, blockedCount);
    }

    /**
     * Track consecutive blocked ticks per gate and flip isJammed once the obstruction has
     * persisted for JAM_THRESHOLD_TICKS, or clear it as soon as placement fully succeeds again.
     * Persists the transition immediately so admins/players see it without waiting for the
     * periodic GateStateSyncTask sweep.
     */
    private void handleJamTracking(CachedGateDoor gate, int blockedCount) {
        if (blockedCount > 0) {
            int consecutiveTicks = jamTickCounters.merge(gate.getId(), 1, Integer::sum);
            if (consecutiveTicks >= JAM_THRESHOLD_TICKS && !gate.isJammed()) {
                gate.setIsJammed(true);
                LOGGER.warning("[GateAnimation] Gate '" + gate.getName() + "' (ID: " + gate.getId()
                    + ") is JAMMED - an obstruction is blocking its door blocks.");
                persistGateState(gate);
            }
        } else {
            jamTickCounters.remove(gate.getId());
            if (gate.isJammed()) {
                gate.setIsJammed(false);
                LOGGER.info("[GateAnimation] Gate '" + gate.getName() + "' (ID: " + gate.getId()
                    + ") is no longer jammed - resuming animation.");
                persistGateState(gate);
            }
        }
    }

    /**
     * Play the gate's open/close sound once, the tick it starts animating.
     */
    private void playGateSoundIfDue(CachedGateDoor gate, AnimationState state, boolean justStarted) {
        if (!justStarted) {
            return;
        }

        Sound sound = state == AnimationState.OPENING ? Sound.BLOCK_CHEST_OPEN : Sound.BLOCK_CHEST_CLOSE;
        playGateSound(gate, sound);
    }

    /**
     * Play a gate open/close sound effect at the gate's anchor point.
     */
    private void playGateSound(CachedGateDoor gate, Sound sound) {
        Vector anchor = gate.getAnchorPoint();
        if (anchor == null) {
            return;
        }
        Location location = new Location(world, anchor.getX(), anchor.getY(), anchor.getZ());
        world.playSound(location, sound, SoundCategory.BLOCKS, 1.0f, GATE_SOUND_PITCH);
    }

    private record BlockPlacement(Vector position, String blockData) {
    }

    private record BlockMove(Vector previousPosition, Vector worldPos) {
    }

    /**
     * One-time defensive resync of the spatial index at animation completion, correcting any
     * drift the per-tick move() calls might have accumulated (e.g. under a lag-induced frame
     * skip, where the assumed single-step "previous frame" doesn't match the actual last frame).
     */
    private void resyncSpatialIndex(CachedGateDoor gate, int frame) {
        List<Vector> positions = GateRestingFramePlacer.restingFramePositions(gate, frame, rasterizationEnabled);

        GateSpatialIndex spatialIndex = gateManager.getSpatialIndex();
        spatialIndex.removeAllForGate(gate.getWorldName(), gate.getId());
        spatialIndex.putAll(gate.getWorldName(), positions, gate.getId());
    }

    private void handleEntityPush(CachedGateDoor gate, int currentFrame) {
        Vector anchor = gate.getAnchorPoint();
        if (anchor == null) {
            return;
        }

        Location origin = new Location(world, anchor.getX(), anchor.getY(), anchor.getZ());
        double radius = entitySearchRadius(gate);

        for (Entity entity : world.getNearbyEntities(origin, radius, radius, radius)) {
            if (entity.isDead() || entity instanceof Display) {
                continue;
            }

            int framesToCollision = CollisionPredictor.predictCollision(gate, entity, currentFrame);
            if (framesToCollision == 0) {
                // Already inside the blocks being rendered this frame; a push cannot save it.
                if (!EntityEvacuator.evacuate(entity, gate)) {
                    EntityPusher.pushEntity(entity, gate);
                }
            } else if (framesToCollision <= ENTITY_COLLISION_FRAMES_THRESHOLD) {
                EntityPusher.pushEntity(entity, gate);
            }
        }
    }

    private double entitySearchRadius(CachedGateDoor gate) {
        int span = Math.max(gate.getGeometryWidth(), Math.max(gate.getGeometryHeight(), gate.getGeometryDepth()));
        Vector motion = gate.getMotionVector();
        double travel = motion != null ? motion.length() : 0.0;
        return Math.max(ENTITY_PUSH_RADIUS, span + travel);
    }

    /**
     * Finish opening animation for a gate.
     *
     * @param gate The gate that finished opening
     */
    private void finishOpening(CachedGateDoor gate) {
        gate.setCurrentState(AnimationState.OPEN);
        gate.setCurrentFrame(gate.getAnimationDurationTicks());

        // Ensure all gate blocks are placed at the open position, mirroring finishClosing's
        // force-placement at frame 0. Without this, a large wall-clock jump (e.g. a main-thread
        // stall causing elapsed time to skip straight past most of the animation in one
        // updateGateBlocks call) leaves blocks stranded wherever they last were computed -
        // updateGateBlocks only ever vacates one tick behind the frame it's given, not every
        // frame since the last call, so a skipped stretch is never cleaned up on its own.
        // Also where Mechanism 1 (rasterized gap-fill) and Mechanism 2 (open-scan pairing)
        // actually converge to their final, fully-correct resting shape - see
        // transitionRestingFrame, which also clears any closed-frame rasterized filler blocks
        // that aren't part of the open frame (the per-tick swing above only vacates each
        // BlockSnapshot's own previous position, never Mechanism 1's extra filler cells).
        GateRestingFramePlacer.transitionRestingFrame(world, gate, 0, gate.getAnimationDurationTicks(), fallbackMaterial, rasterizationEnabled);
        resyncSpatialIndex(gate, gate.getCurrentFrame());

        LOGGER.info("Gate " + gate.getName() + " finished opening");
        gateManager.notifyAnimationCompleted(gate.getId(), AnimationState.OPEN);

        // No WorldGuard region sync here (item 6.2): GateDoor.ClosedRegionData/OpenedRegionData
        // used to be WorldGuard region names (RegionClosedId/RegionOpenedId) that this method
        // toggled via worldGuardIntegration.syncRegions on every open/close; they now hold
        // captured region vertex JSON instead, so that call was removed - passing JSON to
        // RegionManager.getRegion would just silently no-op. worldGuardIntegration itself (and the
        // WorldGuard integration used elsewhere by Town/District/GateStructure) is untouched by
        // this change - only this class's own use of it went away. See
        // WORLDGUARD_REGION_FEASIBILITY.md §9 for the decision.

        if (displayManager != null) {
            displayManager.syncDisplay(gate);
        }

        persistGateState(gate);
    }

    /**
     * Finish closing animation for a gate.
     *
     * @param gate The gate that finished closing
     */
    private void finishClosing(CachedGateDoor gate) {
        gate.setCurrentState(AnimationState.CLOSED);
        gate.setCurrentFrame(0);

        // Ensure all gate blocks are placed at closed position, and clear any open-frame
        // rasterized filler blocks that aren't also part of the closed frame (see
        // transitionRestingFrame).
        GateRestingFramePlacer.transitionRestingFrame(world, gate, gate.getAnimationDurationTicks(), 0, fallbackMaterial, rasterizationEnabled);
        resyncSpatialIndex(gate, 0);

        LOGGER.info("Gate " + gate.getName() + " finished closing");
        gateManager.notifyAnimationCompleted(gate.getId(), AnimationState.CLOSED);

        // No WorldGuard region sync here - see the matching comment in finishOpening above.

        if (displayManager != null) {
            displayManager.syncDisplay(gate);
        }

        persistGateState(gate);
    }

    /**
     * Persist the gate's terminal state (opened/destroyed) to the API asynchronously,
     * so the DB stays in sync as soon as an open/close animation completes.
     */
    private void persistGateState(CachedGateDoor gate) {
        if (gateDoorsApi == null) {
            return;
        }

        boolean isDestroyed = gate.isDestroyed();
        String openedState = GateDoorOpenStateMapper.toWireValue(gate.getCurrentState(), gate.isJammed());

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    gateDoorsApi.updateState(gate.getId(), openedState, isDestroyed).join();
                    LOGGER.fine("Gate state persisted to API: " + gate.getName() +
                        " (openedState=" + openedState + ", destroyed=" + isDestroyed + ")");
                } catch (Exception e) {
                    LOGGER.warning("Failed to persist gate state for '" + gate.getName() + "': " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Check server TPS to detect lag.
     * If TPS < 15, enable lag mode to skip animation frames.
     */
    private void checkServerLag() {
        long now = System.currentTimeMillis();
        
        if (now - lastLagCheck < LAG_CHECK_INTERVAL) {
            return;
        }

        lastLagCheck = now;

        try {
            // Get server TPS (Paper API)
            double tps = Bukkit.getTPS()[0]; // Last 1 minute average
            isLagging = tps < LAG_TPS_THRESHOLD;

            if (isLagging) {
                LOGGER.warning("Server lagging (TPS: " + String.format("%.2f", tps) + 
                              "), gate animations may skip frames");
            }
        } catch (Exception e) {
            // TPS API not available or error occurred
            isLagging = false;
        }
    }

    /**
     * Get the current lag status.
     * 
     * @return True if server is currently lagging
     */
    public boolean isLagging() {
        return isLagging;
    }
}
