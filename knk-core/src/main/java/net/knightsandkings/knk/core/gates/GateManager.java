package net.knightsandkings.knk.core.gates;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.domain.gates.CachedGateStructure;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Manager for gate structures in the plugin.
 * Caches gate doors (and their parent structures) in memory and provides access to gate data and
 * state management. DTO loading and conversion is handled by adapters in the framework layer
 * (knk-paper).
 *
 * <p>A "gate" in this plugin's commands/animation/interaction has always meant one animated door
 * (today's {@link CachedGateDoor}) - item 5's multi-door support
 * (docs/features/gate-structure-animation/GATESTRUCTURE_QOL_IMPLEMENTATION_PLAN.md) adds a
 * separate, thinner {@link CachedGateStructure} cache for the structure-level identity and
 * cascading-override data every door on that structure shares.
 */
public class GateManager {
    private static final Logger LOGGER = Logger.getLogger(GateManager.class.getName());

    private final Map<Integer, CachedGateDoor> gateCache;
    private final Map<Integer, CachedGateStructure> structureCache;
    private final Map<Integer, Consumer<AnimationState>> animationCompletionCallbacks;
    private final GateSpatialIndex spatialIndex;
    private Supplier<CompletableFuture<Void>> reloadAction;

    public GateManager() {
        // ConcurrentHashMap: GateLoaderAdapter populates these from N parallel async HTTP
        // completion callbacks (one per gate structure, via CompletableFuture.allOf), so
        // concurrent cacheGate/cacheStructure calls from different threads are expected.
        this.gateCache = new ConcurrentHashMap<>();
        this.structureCache = new ConcurrentHashMap<>();
        this.animationCompletionCallbacks = new ConcurrentHashMap<>();
        this.spatialIndex = new GateSpatialIndex();
    }

    /**
     * Spatial index of door-block world positions, kept in sync with every code path that
     * moves/places/removes a gate's animated blocks. See GateSpatialIndex for update contract.
     */
    public GateSpatialIndex getSpatialIndex() {
        return spatialIndex;
    }

    /**
     * Load all active gate structures from the API.
     * This method should be called during plugin startup.
     * DTO loading and conversion is delegated to framework adapters.
     *
     * @return CompletableFuture that completes when gates are reloaded
     */
    public CompletableFuture<Void> loadGatesFromApi() {
        if (reloadAction == null) {
            LOGGER.warning("No gate loader has been configured");
            return CompletableFuture.completedFuture(null);
        }

        return reloadAction.get();
    }

    /**
     * Configure the framework-owned loader used for startup and admin reloads.
     *
     * @param reloadAction asynchronous loader which refreshes the runtime cache
     */
    public void setReloadAction(Supplier<CompletableFuture<Void>> reloadAction) {
        this.reloadAction = reloadAction;
    }

    /**
     * Register the one-shot callback that receives the terminal animation state.
     *
     * @param gateId gate (door) being animated
     * @param callback invoked with OPEN or CLOSED when animation finishes
     */
    public void setAnimationCompletionCallback(int gateId, Consumer<AnimationState> callback) {
        if (callback != null) {
            animationCompletionCallbacks.put(gateId, callback);
        }
    }

    /**
     * Notify and remove the callback associated with a completed gate animation.
     *
     * @param gateId completed gate (door) ID
     * @param state terminal animation state
     */
    public void notifyAnimationCompleted(int gateId, AnimationState state) {
        Consumer<AnimationState> callback = animationCompletionCallbacks.remove(gateId);
        if (callback != null) {
            callback.accept(state);
        }
    }

    /**
     * Cache a gate door that has been loaded and converted by a framework adapter.
     * This is the primary entry point for gate doors created outside of this class.
     * Called by adapters in knk-paper after DTO conversion.
     *
     * @param gate The CachedGateDoor instance to cache
     */
    public void cacheGate(CachedGateDoor gate) {
        if (gate == null) {
            LOGGER.warning("Attempted to cache null gate");
            return;
        }

        CachedGateDoor previous = gateCache.get(gate.getId());
        if (previous != null) {
            spatialIndex.removeAll(previous.getWorldName(), doorBlockPositions(previous, previous.getCurrentFrame()));
        }

        gateCache.put(gate.getId(), gate);
        spatialIndex.putAll(gate.getWorldName(), doorBlockPositions(gate, gate.getCurrentFrame()), gate.getId());

        LOGGER.info("Cached gate: " + gate.getName() + " (ID: " + gate.getId() +
                   ") with " + gate.getBlocks().size() + " blocks");
    }

    /**
     * Cache a gate structure's structure-level data (identity, siege fields, cascading
     * overrides). Called by GateLoaderAdapter alongside {@link #cacheGate}.
     *
     * @param structure The CachedGateStructure instance to cache
     */
    public void cacheStructure(CachedGateStructure structure) {
        if (structure == null) {
            LOGGER.warning("Attempted to cache null gate structure");
            return;
        }
        structureCache.put(structure.getId(), structure);
    }

    /**
     * World positions of a gate's door blocks at the given animation frame, skipping any
     * block clipped away by ClipToGeometryBounds (see GateFrameCalculator).
     */
    private static List<Vector> doorBlockPositions(CachedGateDoor gate, int frame) {
        List<Vector> positions = new ArrayList<>();
        for (BlockSnapshot block : gate.getBlocks()) {
            if (block == null) {
                continue;
            }
            Vector position = GateFrameCalculator.calculateBlockPosition(gate, block, frame);
            if (position != null) {
                positions.add(position);
            }
        }
        return positions;
    }

    // === Public API for accessing gates ===

    /**
     * Get a cached gate door by ID.
     *
     * @param id Gate door ID
     * @return CachedGateDoor or null if not found
     */
    public CachedGateDoor getGate(int id) {
        return gateCache.get(id);
    }

    /**
     * Get a cached gate door by name. Door names are only guaranteed unique within their parent
     * structure (decision 5.0-D), not globally - this returns the first match.
     *
     * @param name Gate door name
     * @return CachedGateDoor or null if not found
     */
    public CachedGateDoor getGateByName(String name) {
        return gateCache.values().stream()
            .filter(gate -> gate.getName().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }

    /**
     * Get all cached gate doors.
     *
     * @return Map of gate door ID to CachedGateDoor
     */
    public Map<Integer, CachedGateDoor> getAllGates() {
        return new HashMap<>(gateCache);
    }

    /**
     * Get a cached gate structure by ID.
     *
     * @param id Gate structure ID
     * @return CachedGateStructure or null if not found
     */
    public CachedGateStructure getStructure(int id) {
        return structureCache.get(id);
    }

    /**
     * Get a cached gate structure by name (case-insensitive, first match - structure names are
     * unique in the backend, so this is unambiguous in practice).
     *
     * @param name Gate structure name
     * @return CachedGateStructure or null if not found
     */
    public CachedGateStructure getStructureByName(String name) {
        return structureCache.values().stream()
            .filter(structure -> structure.getName().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }

    /**
     * Get every cached door belonging to a gate structure.
     *
     * @param gateStructureId Parent gate structure ID
     * @return Doors belonging to that structure, in no particular order
     */
    public List<CachedGateDoor> getDoorsForStructure(int gateStructureId) {
        List<CachedGateDoor> doors = new ArrayList<>();
        for (CachedGateDoor door : gateCache.values()) {
            if (door.getGateStructureId() == gateStructureId) {
                doors.add(door);
            }
        }
        return doors;
    }

    /**
     * Reload gates from the API.
     * Clears the cache and reloads all gates.
     *
     * @return CompletableFuture that completes when reload is done
     */
    public CompletableFuture<Void> reloadGates() {
        LOGGER.info("Reloading gates from API...");
        return loadGatesFromApi();
    }

    // === State Machine Methods ===

    /**
     * Open a gate, starting the opening animation.
     *
     * @param gateId Gate ID
     * @return True if gate started opening, false if already open or animating
     */
    public boolean openGate(int gateId) {
        CachedGateDoor gate = gateCache.get(gateId);

        if (gate == null) {
            LOGGER.warning("Cannot open gate: Gate ID " + gateId + " not found");
            return false;
        }

        // Check if gate is already open or opening
        AnimationState currentState = gate.getCurrentState();
        if (currentState == AnimationState.OPEN || currentState == AnimationState.OPENING) {
            LOGGER.fine("Gate " + gate.getName() + " is already open or opening");
            return false;
        }

        // Check if gate is active and not destroyed
        if (!gate.isEffectivelyActive() || gate.isEffectivelyDestroyed()) {
            LOGGER.warning("Cannot open gate " + gate.getName() + ": Gate is inactive or destroyed");
            return false;
        }

        // Start opening animation
        gate.setCurrentState(AnimationState.OPENING);
        gate.setCurrentFrame(0);
        gate.setAnimationStartTime(System.currentTimeMillis());

        LOGGER.info("Opening gate: " + gate.getName() + " (ID: " + gateId + ")");
        return true;
    }

    /**
     * Close a gate, starting the closing animation.
     *
     * @param gateId Gate ID
     * @return True if gate started closing, false if already closed or animating
     */
    public boolean closeGate(int gateId) {
        CachedGateDoor gate = gateCache.get(gateId);

        if (gate == null) {
            LOGGER.warning("Cannot close gate: Gate ID " + gateId + " not found");
            return false;
        }

        // Check if gate is already closed or closing
        AnimationState currentState = gate.getCurrentState();
        if (currentState == AnimationState.CLOSED || currentState == AnimationState.CLOSING) {
            LOGGER.fine("Gate " + gate.getName() + " is already closed or closing");
            return false;
        }

        // Check if gate is active
        if (!gate.isEffectivelyActive()) {
            LOGGER.warning("Cannot close gate " + gate.getName() + ": Gate is inactive");
            return false;
        }

        // Start closing animation
        gate.setCurrentState(AnimationState.CLOSING);
        gate.setCurrentFrame(gate.getAnimationDurationTicks());
        gate.setAnimationStartTime(System.currentTimeMillis());

        LOGGER.info("Closing gate: " + gate.getName() + " (ID: " + gateId + ")");
        return true;
    }

    /**
     * Toggle a gate between open and closed states.
     *
     * @param gateId Gate ID
     * @return True if gate state was toggled
     */
    public boolean toggleGate(int gateId) {
        CachedGateDoor gate = gateCache.get(gateId);

        if (gate == null) {
            return false;
        }

        AnimationState currentState = gate.getCurrentState();

        if (currentState == AnimationState.CLOSED) {
            return openGate(gateId);
        } else if (currentState == AnimationState.OPEN) {
            return closeGate(gateId);
        } else {
            LOGGER.fine("Cannot toggle gate " + gate.getName() + ": Gate is currently animating");
            return false;
        }
    }

    /**
     * Force a gate to a specific state immediately (skip animation).
     * Use with caution - mainly for admin commands or error recovery.
     *
     * @param gateId Gate ID
     * @param isOpened Target state (true = open, false = closed)
     */
    public void forceGateState(int gateId, boolean isOpened) {
        CachedGateDoor gate = gateCache.get(gateId);

        if (gate == null) {
            LOGGER.warning("Cannot force gate state: Gate ID " + gateId + " not found");
            return;
        }

        if (isOpened) {
            gate.setCurrentState(AnimationState.OPEN);
            gate.setCurrentFrame(gate.getAnimationDurationTicks());
        } else {
            gate.setCurrentState(AnimationState.CLOSED);
            gate.setCurrentFrame(0);
        }

        LOGGER.info("Forced gate " + gate.getName() + " to " + (isOpened ? "OPEN" : "CLOSED"));
    }

    /**
     * Check if a gate is currently animating.
     *
     * @param gateId Gate ID
     * @return True if gate is opening or closing
     */
    public boolean isGateAnimating(int gateId) {
        CachedGateDoor gate = gateCache.get(gateId);
        return gate != null && gate.isAnimating();
    }

    /**
     * Get the current animation progress of a gate.
     *
     * @param gateId Gate ID
     * @return Progress from 0.0 (closed) to 1.0 (open), or -1.0 if gate not found
     */
    public double getGateProgress(int gateId) {
        CachedGateDoor gate = gateCache.get(gateId);

        if (gate == null) {
            return -1.0;
        }

        int totalFrames = gate.getAnimationDurationTicks();
        if (totalFrames <= 0) {
            return 0.0;
        }

        return (double) gate.getCurrentFrame() / totalFrames;
    }
}
