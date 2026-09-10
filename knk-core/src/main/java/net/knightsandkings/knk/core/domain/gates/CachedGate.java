package net.knightsandkings.knk.core.domain.gates;

import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cached representation of a gate structure with precomputed animation data.
 * This model is optimized for runtime animation calculations.
 */
public class CachedGate {
    // === Core Identity ===
    private final int id;
    private final String name;
    private final String gateType;
    private final String motionType;
    private final String geometryDefinitionMode;
    private final String faceDirection;
    private String worldName;

    // === Animation State (mutable) ===
    private AnimationState currentState;
    private int currentFrame;
    private long animationStartTime;

    // === Animation Configuration ===
    private final int animationDurationTicks;
    private final int animationTickRate;

    // === Geometry ===
    private final Vector anchorPoint;
    private final int geometryWidth;
    private final int geometryHeight;
    private final int geometryDepth;
    private boolean clipToGeometryBounds;

    // === Precomputed Local Basis Vectors ===
    private Vector uAxis;  // Width direction
    private Vector vAxis;  // Height direction
    private Vector nAxis;  // Normal/motion direction

    // === Precomputed Lattice Step Vectors ===
    // Shortest integer vector pointing along uAxis/vAxis/nAxis (see VectorMath.primitiveLatticeStep).
    // Identical to the unit axis for cardinal gates, but for a diagonal gate this is e.g. (1,0,1)
    // instead of (0.7071,0,0.7071) - used wherever an integer index must land on an adjacent
    // Minecraft block (scanning, LATERAL motion distance, geometry-bounds clipping).
    private Vector uStep;
    private Vector vStep;
    private Vector nStep;

    // Sublattice index (VectorMath.sublatticeIndex(uStep)) - 1 for a cardinal-hinge ROTATION gate
    // (no gaps), >1 for a diagonal one. Drives Mechanism 1 (automatic rasterized gap-fill) in
    // GateAnimationTask/GateFrameCalculator - see ROTATION_GAP_FILL_DESIGN.md.
    private long sublatticeIndex = 1;

    // === Precomputed Motion ===
    private Vector motionVector;  // Direction and magnitude of motion
    private Vector hingeAxis;     // For rotation gates

    // === Block Data ===
    private final List<BlockSnapshot> blocks;

    // === Mechanism 2: manually-scanned open state (ROTATION_GAP_FILL_DESIGN.md) ===
    // Optional second physical anchor a scanned open shape was built/scanned relative to.
    private Vector openAnchorPoint;
    // Empty (never null) when no open-state scan exists for this gate - that emptiness is itself
    // the trigger: Mechanism 2 only ever engages for a block with an entry in openBlockPairing.
    private final List<BlockSnapshot> openBlocks = new ArrayList<>();
    // Closed BlockSnapshot.getId() -> its nearest-neighbor-paired open BlockSnapshot, computed
    // once at load time (GateLoaderAdapter) via GateBlockPairing. A block with no entry here has
    // no open-scan counterpart and keeps today's exact procedural behavior.
    private Map<Integer, BlockSnapshot> openBlockPairing = new HashMap<>();

    // === Health & State ===
    private double healthCurrent;
    private double healthMax;
    private boolean isActive;
    private boolean isDestroyed;
    private boolean isInvincible;
    // Orthogonal to AnimationState: true when the current OPENING/CLOSING animation is
    // stalled by a non-replaceable obstruction (see GateAnimationTask.updateGateBlocks).
    private boolean isJammed;

    // === Rotation (for DRAWBRIDGE/DOUBLE_DOORS) ===
    private final int rotationMaxAngleDegrees;

    // === WorldGuard Integration ===
    private String regionClosedId;
    private String regionOpenedId;

    // === Respawn System ===
    private boolean canRespawn;
    private int respawnRateSeconds;
    private long respawnScheduledTime; // When respawn task is scheduled for

    // === Health Display ===
    private boolean showHealthDisplay = true;
    private String healthDisplayMode = "ALWAYS";
    private int healthDisplayYOffset = 2;
    // Optional admin-configured override for the info hover's world position. Null means "compute
    // it" (see GateDisplayManager.calculateDisplayLocation), which is the common case.
    private Vector infoDisplayLocation;
    private String gateNameDisplayMode = "ALWAYS";
    private String statusDisplayMode = "ALWAYS";

    // === Siege Integration ===
    private Integer currentSiegeId;

    // === Pass-Through ===
    private boolean allowPassThrough;
    private int passThroughDurationSeconds = 2;

    // === Continuous Damage (Fire) ===
    // World-block position (integer-valued Vector) -> epoch millis when the fire on that block
    // expires. Populated by GateFireSystem.igniteBlock, drained by GateFireSystem.tick. Only
    // meaningful while CLOSED - that's the only state where a door block's world position is
    // stable enough for a burn to track a specific cell.
    private final Map<Vector, Long> burningBlocks = new HashMap<>();

    public CachedGate(int id, String name, String gateType, String motionType, String geometryDefinitionMode,
                      int animationDurationTicks, int animationTickRate,
                      Vector anchorPoint, int geometryWidth, int geometryHeight, int geometryDepth,
                      double healthCurrent, double healthMax, boolean isActive, boolean isDestroyed, 
                      boolean isInvincible, int rotationMaxAngleDegrees, String faceDirection) {
        this.id = id;
        this.name = name;
        this.gateType = gateType;
        this.motionType = motionType;
        this.geometryDefinitionMode = geometryDefinitionMode;
        this.faceDirection = faceDirection;
        this.worldName = "";
        this.animationDurationTicks = animationDurationTicks;
        this.animationTickRate = animationTickRate;
        this.anchorPoint = anchorPoint;
        this.geometryWidth = geometryWidth;
        this.geometryHeight = geometryHeight;
        this.geometryDepth = geometryDepth;
        this.healthCurrent = healthCurrent;
        this.healthMax = healthMax;
        this.isActive = isActive;
        this.isDestroyed = isDestroyed;
        this.isInvincible = isInvincible;
        this.rotationMaxAngleDegrees = rotationMaxAngleDegrees;
        this.blocks = new ArrayList<>();
        this.currentState = AnimationState.CLOSED;
        this.currentFrame = 0;
        this.animationStartTime = 0;
        this.regionClosedId = "";
        this.regionOpenedId = "";
        this.canRespawn = true;
        this.respawnRateSeconds = 300;
        this.respawnScheduledTime = 0;
    }

    // === Getters ===

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getGateType() {
        return gateType;
    }

    public String getMotionType() {
        return motionType;
    }

    public String getGeometryDefinitionMode() {
        return geometryDefinitionMode;
    }

    public String getFaceDirection() {
        return faceDirection;
    }

    public String getWorldName() {
        return worldName;
    }

    public AnimationState getCurrentState() {
        return currentState;
    }

    public int getCurrentFrame() {
        return currentFrame;
    }

    public long getAnimationStartTime() {
        return animationStartTime;
    }

    public int getAnimationDurationTicks() {
        return animationDurationTicks;
    }

    public int getAnimationTickRate() {
        return animationTickRate;
    }

    public Vector getAnchorPoint() {
        return anchorPoint;
    }

    public int getGeometryWidth() {
        return geometryWidth;
    }

    public int getGeometryHeight() {
        return geometryHeight;
    }

    public int getGeometryDepth() {
        return geometryDepth;
    }

    public boolean isClipToGeometryBounds() {
        return clipToGeometryBounds;
    }

    public void setClipToGeometryBounds(boolean clipToGeometryBounds) {
        this.clipToGeometryBounds = clipToGeometryBounds;
    }

    public Vector getUAxis() {
        return uAxis;
    }

    public Vector getVAxis() {
        return vAxis;
    }

    public Vector getNAxis() {
        return nAxis;
    }

    public Vector getUStep() {
        return uStep;
    }

    public Vector getVStep() {
        return vStep;
    }

    public Vector getNStep() {
        return nStep;
    }

    public long getSublatticeIndex() {
        return sublatticeIndex;
    }

    public Vector getOpenAnchorPoint() {
        return openAnchorPoint;
    }

    public List<BlockSnapshot> getOpenBlocks() {
        return openBlocks;
    }

    public Vector getMotionVector() {
        return motionVector;
    }

    public Vector getHingeAxis() {
        return hingeAxis;
    }

    public List<BlockSnapshot> getBlocks() {
        return blocks;
    }

    public double getHealthCurrent() {
        return healthCurrent;
    }

    public double getHealthMax() {
        return healthMax;
    }

    public boolean isActive() {
        return isActive;
    }

    public boolean isDestroyed() {
        return isDestroyed;
    }

    public boolean isInvincible() {
        return isInvincible;
    }

    public boolean isJammed() {
        return isJammed;
    }

    public int getRotationMaxAngleDegrees() {
        return rotationMaxAngleDegrees;
    }

    public String getRegionClosedId() {
        return regionClosedId;
    }

    public String getRegionOpenedId() {
        return regionOpenedId;
    }

    public boolean isCanRespawn() {
        return canRespawn;
    }

    public int getRespawnRateSeconds() {
        return respawnRateSeconds;
    }

    public long getRespawnScheduledTime() {
        return respawnScheduledTime;
    }

    public boolean isShowHealthDisplay() {
        return showHealthDisplay;
    }

    public String getHealthDisplayMode() {
        return healthDisplayMode;
    }

    public int getHealthDisplayYOffset() {
        return healthDisplayYOffset;
    }

    public Vector getInfoDisplayLocation() {
        return infoDisplayLocation;
    }

    public String getGateNameDisplayMode() {
        return gateNameDisplayMode;
    }

    public String getStatusDisplayMode() {
        return statusDisplayMode;
    }

    public Integer getCurrentSiegeId() {
        return currentSiegeId;
    }

    public boolean isAllowPassThrough() {
        return allowPassThrough;
    }

    public int getPassThroughDurationSeconds() {
        return passThroughDurationSeconds;
    }

    // === Setters for Mutable State ===

    public void setCurrentState(AnimationState currentState) {
        this.currentState = currentState;
    }

    public void setCurrentFrame(int currentFrame) {
        this.currentFrame = currentFrame;
    }

    public void setAnimationStartTime(long animationStartTime) {
        this.animationStartTime = animationStartTime;
    }

    public void setHealthCurrent(double healthCurrent) {
        this.healthCurrent = healthCurrent;
    }

    public void setHealthMax(double healthMax) {
        this.healthMax = healthMax;
    }

    public void setIsDestroyed(boolean isDestroyed) {
        this.isDestroyed = isDestroyed;
    }

    public void setIsActive(boolean isActive) {
        this.isActive = isActive;
    }

    public void setIsInvincible(boolean isInvincible) {
        this.isInvincible = isInvincible;
    }

    public void setIsJammed(boolean isJammed) {
        this.isJammed = isJammed;
    }

    public void setRegionClosedId(String regionClosedId) {
        this.regionClosedId = regionClosedId != null ? regionClosedId : "";
    }

    public void setRegionOpenedId(String regionOpenedId) {
        this.regionOpenedId = regionOpenedId != null ? regionOpenedId : "";
    }

    public void setCanRespawn(boolean canRespawn) {
        this.canRespawn = canRespawn;
    }

    public void setRespawnRateSeconds(int respawnRateSeconds) {
        this.respawnRateSeconds = respawnRateSeconds;
    }

    public void setRespawnScheduledTime(long respawnScheduledTime) {
        this.respawnScheduledTime = respawnScheduledTime;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName != null ? worldName : "";
    }

    public void setShowHealthDisplay(boolean showHealthDisplay) {
        this.showHealthDisplay = showHealthDisplay;
    }

    public void setHealthDisplayMode(String healthDisplayMode) {
        this.healthDisplayMode = healthDisplayMode != null ? healthDisplayMode : "ALWAYS";
    }

    public void setHealthDisplayYOffset(int healthDisplayYOffset) {
        this.healthDisplayYOffset = healthDisplayYOffset;
    }

    public void setInfoDisplayLocation(Vector infoDisplayLocation) {
        this.infoDisplayLocation = infoDisplayLocation;
    }

    public void setGateNameDisplayMode(String gateNameDisplayMode) {
        this.gateNameDisplayMode = gateNameDisplayMode != null ? gateNameDisplayMode : "ALWAYS";
    }

    public void setStatusDisplayMode(String statusDisplayMode) {
        this.statusDisplayMode = statusDisplayMode != null ? statusDisplayMode : "ALWAYS";
    }

    public void setCurrentSiegeId(Integer currentSiegeId) {
        this.currentSiegeId = currentSiegeId;
    }

    public void setAllowPassThrough(boolean allowPassThrough) {
        this.allowPassThrough = allowPassThrough;
    }

    public void setPassThroughDurationSeconds(int passThroughDurationSeconds) {
        this.passThroughDurationSeconds = passThroughDurationSeconds;
    }

    // === Setters for Precomputed Data ===

    public void setUAxis(Vector uAxis) {
        this.uAxis = uAxis;
    }

    public void setVAxis(Vector vAxis) {
        this.vAxis = vAxis;
    }

    public void setNAxis(Vector nAxis) {
        this.nAxis = nAxis;
    }

    public void setUStep(Vector uStep) {
        this.uStep = uStep;
    }

    public void setVStep(Vector vStep) {
        this.vStep = vStep;
    }

    public void setNStep(Vector nStep) {
        this.nStep = nStep;
    }

    public void setSublatticeIndex(long sublatticeIndex) {
        this.sublatticeIndex = sublatticeIndex;
    }

    public void setOpenAnchorPoint(Vector openAnchorPoint) {
        this.openAnchorPoint = openAnchorPoint;
    }

    /**
     * Closed BlockSnapshot.getId() -> paired open BlockSnapshot, per Decision 2/6 in
     * ROTATION_GAP_FILL_DESIGN.md (nearest-neighbor for ROTATION, index-position for
     * VERTICAL/LATERAL - computed by the caller, GateLoaderAdapter). Replaces any existing pairing.
     */
    public void setOpenBlockPairing(Map<Integer, BlockSnapshot> openBlockPairing) {
        this.openBlockPairing = openBlockPairing != null ? openBlockPairing : new HashMap<>();
    }

    public void setMotionVector(Vector motionVector) {
        this.motionVector = motionVector;
    }

    public void setHingeAxis(Vector hingeAxis) {
        this.hingeAxis = hingeAxis;
    }

    // === Helper Methods ===

    public void addBlock(BlockSnapshot block) {
        this.blocks.add(block);
    }

    public void addOpenBlock(BlockSnapshot block) {
        this.openBlocks.add(block);
    }

    /**
     * The open-state block paired with the given closed-state block's id, or null when that
     * block has no counterpart in a scanned open state (either the gate has none at all, or this
     * particular block was left unpaired - see Decision 1 in ROTATION_GAP_FILL_DESIGN.md).
     */
    public BlockSnapshot getPairedOpenBlock(int closedBlockId) {
        return openBlockPairing.get(closedBlockId);
    }

    public boolean isAnimating() {
        return currentState == AnimationState.OPENING || currentState == AnimationState.CLOSING;
    }

    /**
     * Mutable map of currently-burning door-block world positions to their fire-expiry
     * timestamp (epoch millis). Owned and managed by GateFireSystem.
     */
    public Map<Vector, Long> getBurningBlocks() {
        return burningBlocks;
    }

    public boolean isOnFire() {
        return !burningBlocks.isEmpty();
    }
}
