package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * DTO for a single gate door from the Web API. Maps to GateDoorDto from knk-web-api - see
 * docs/features/gate-structure-animation/GATESTRUCTURE_QOL_IMPLEMENTATION_PLAN.md item 5. Holds
 * every field that used to live directly on GateStructureDto and is meaningful per-door
 * (geometry, animation, health, block snapshots).
 */
public class GateDoorDto {

    @JsonProperty("id")
    private Integer id;

    @JsonProperty("gateStructureId")
    private Integer gateStructureId;

    // === Identity ===
    @JsonProperty("name")
    private String name;

    // === Health/Lifecycle ===
    @JsonProperty("healthCurrent")
    private Double healthCurrent;

    @JsonProperty("healthMax")
    private Double healthMax;

    @JsonProperty("respawnRateSeconds")
    private Integer respawnRateSeconds;

    // === Core Door State (cascade-overridable from GateStructure) ===
    @JsonProperty("isActive")
    private Boolean isActive;

    @JsonProperty("canRespawn")
    private Boolean canRespawn;

    @JsonProperty("isDestroyed")
    private Boolean isDestroyed;

    @JsonProperty("isInvincible")
    private Boolean isInvincible;

    // Replaces the legacy isOpened+isJammed boolean pair - one of CLOSED/OPENING/OPEN/CLOSING/JAMMED.
    @JsonProperty("openedState")
    private String openedState;

    // === Gate Type & Animation Configuration ===
    @JsonProperty("gateType")
    private String gateType;

    @JsonProperty("motionType")
    private String motionType;

    @JsonProperty("geometryDefinitionMode")
    private String geometryDefinitionMode;

    @JsonProperty("animationDurationTicks")
    private Integer animationDurationTicks;

    @JsonProperty("animationTickRate")
    private Integer animationTickRate;

    @JsonProperty("faceDirection")
    private String faceDirection;

    // === PLANE_GRID Geometry ===
    @JsonProperty("anchorPoint")
    @JsonDeserialize(using = GateStructureDto.CoordinateStringDeserializer.class)
    private String anchorPoint;

    @JsonProperty("openAnchorPoint")
    @JsonDeserialize(using = GateStructureDto.CoordinateStringDeserializer.class)
    private String openAnchorPoint;

    @JsonProperty("referencePoint1")
    @JsonDeserialize(using = GateStructureDto.CoordinateStringDeserializer.class)
    private String referencePoint1;

    @JsonProperty("referencePoint2")
    @JsonDeserialize(using = GateStructureDto.CoordinateStringDeserializer.class)
    private String referencePoint2;

    @JsonProperty("geometryWidth")
    private Integer geometryWidth;

    @JsonProperty("geometryHeight")
    private Integer geometryHeight;

    @JsonProperty("geometryDepth")
    private Integer geometryDepth;

    @JsonProperty("motionDistanceBlocks")
    private Integer motionDistanceBlocks;

    @JsonProperty("clipToGeometryBounds")
    private Boolean clipToGeometryBounds;

    // === FLOOD_FILL Geometry ===
    @JsonProperty("seedBlocks")
    private String seedBlocks;

    @JsonProperty("scanMaxBlocks")
    private Integer scanMaxBlocks;

    @JsonProperty("scanMaxRadius")
    private Integer scanMaxRadius;

    @JsonProperty("scanMaterialWhitelist")
    private String scanMaterialWhitelist;

    @JsonProperty("scanMaterialBlacklist")
    private String scanMaterialBlacklist;

    @JsonProperty("scanPlaneConstraint")
    private Boolean scanPlaneConstraint;

    // === Block Rendering ===
    @JsonProperty("fallbackMaterialRefId")
    private Integer fallbackMaterialRefId;

    @JsonProperty("tileEntityPolicy")
    private String tileEntityPolicy;

    // === Rotation ===
    @JsonProperty("rotationMaxAngleDegrees")
    private Integer rotationMaxAngleDegrees;

    @JsonProperty("hingeAxis")
    @JsonDeserialize(using = GateStructureDto.CoordinateStringDeserializer.class)
    private String hingeAxis;

    @JsonProperty("mirrorRotation")
    private Boolean mirrorRotation;

    // === Double Doors ===
    @JsonProperty("leftDoorSeedBlock")
    @JsonDeserialize(using = GateStructureDto.CoordinateStringDeserializer.class)
    private String leftDoorSeedBlock;

    @JsonProperty("rightDoorSeedBlock")
    @JsonDeserialize(using = GateStructureDto.CoordinateStringDeserializer.class)
    private String rightDoorSeedBlock;

    // === Region-based geometry (GeometryDefinitionMode.REGION; item 6) ===
    @JsonProperty("closedRegionData")
    private String closedRegionData;

    @JsonProperty("openedRegionData")
    private String openedRegionData;

    // === Pass-Through System (cascade-overridable) ===
    @JsonProperty("allowPassThrough")
    private Boolean allowPassThrough;

    @JsonProperty("passThroughDurationSeconds")
    private Integer passThroughDurationSeconds;

    // === Display Position ===
    @JsonProperty("infoDisplayLocation")
    @JsonDeserialize(using = GateStructureDto.CoordinateStringDeserializer.class)
    private String infoDisplayLocation;

    // === Health/Name/Status Display Configuration (cascade-overridable) ===
    @JsonProperty("showHealthDisplay")
    private Boolean showHealthDisplay;

    @JsonProperty("healthDisplayMode")
    private String healthDisplayMode;

    @JsonProperty("healthDisplayYOffset")
    private Integer healthDisplayYOffset;

    @JsonProperty("gateNameDisplayMode")
    private String gateNameDisplayMode;

    @JsonProperty("statusDisplayMode")
    private String statusDisplayMode;

    // === Door-Name Display (per-door only, no structure-level override) ===
    @JsonProperty("doorNameDisplayMode")
    private String doorNameDisplayMode;

    // === Combat System: Continuous Damage (cascade-overridable) ===
    @JsonProperty("allowContinuousDamage")
    private Boolean allowContinuousDamage;

    @JsonProperty("continuousDamageMultiplier")
    private Double continuousDamageMultiplier;

    @JsonProperty("continuousDamageDurationSeconds")
    private Integer continuousDamageDurationSeconds;

    // === Block Snapshots ===
    @JsonProperty("blockSnapshots")
    private List<GateBlockSnapshotDto> blockSnapshots;

    @JsonProperty("openedBlockSnapshots")
    private List<GateBlockSnapshotDto> openedBlockSnapshots;

    // === Getters and Setters ===

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getGateStructureId() {
        return gateStructureId;
    }

    public void setGateStructureId(Integer gateStructureId) {
        this.gateStructureId = gateStructureId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Double getHealthCurrent() {
        return healthCurrent;
    }

    public void setHealthCurrent(Double healthCurrent) {
        this.healthCurrent = healthCurrent;
    }

    public Double getHealthMax() {
        return healthMax;
    }

    public void setHealthMax(Double healthMax) {
        this.healthMax = healthMax;
    }

    public Integer getRespawnRateSeconds() {
        return respawnRateSeconds;
    }

    public void setRespawnRateSeconds(Integer respawnRateSeconds) {
        this.respawnRateSeconds = respawnRateSeconds;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Boolean getCanRespawn() {
        return canRespawn;
    }

    public void setCanRespawn(Boolean canRespawn) {
        this.canRespawn = canRespawn;
    }

    public Boolean getIsDestroyed() {
        return isDestroyed;
    }

    public void setIsDestroyed(Boolean isDestroyed) {
        this.isDestroyed = isDestroyed;
    }

    public Boolean getIsInvincible() {
        return isInvincible;
    }

    public void setIsInvincible(Boolean isInvincible) {
        this.isInvincible = isInvincible;
    }

    public String getOpenedState() {
        return openedState;
    }

    public void setOpenedState(String openedState) {
        this.openedState = openedState;
    }

    public String getGateType() {
        return gateType;
    }

    public void setGateType(String gateType) {
        this.gateType = gateType;
    }

    public String getMotionType() {
        return motionType;
    }

    public void setMotionType(String motionType) {
        this.motionType = motionType;
    }

    public String getGeometryDefinitionMode() {
        return geometryDefinitionMode;
    }

    public void setGeometryDefinitionMode(String geometryDefinitionMode) {
        this.geometryDefinitionMode = geometryDefinitionMode;
    }

    public Integer getAnimationDurationTicks() {
        return animationDurationTicks;
    }

    public void setAnimationDurationTicks(Integer animationDurationTicks) {
        this.animationDurationTicks = animationDurationTicks;
    }

    public Integer getAnimationTickRate() {
        return animationTickRate;
    }

    public void setAnimationTickRate(Integer animationTickRate) {
        this.animationTickRate = animationTickRate;
    }

    public String getFaceDirection() {
        return faceDirection;
    }

    public void setFaceDirection(String faceDirection) {
        this.faceDirection = faceDirection;
    }

    public String getAnchorPoint() {
        return anchorPoint;
    }

    public void setAnchorPoint(String anchorPoint) {
        this.anchorPoint = anchorPoint;
    }

    public String getOpenAnchorPoint() {
        return openAnchorPoint;
    }

    public void setOpenAnchorPoint(String openAnchorPoint) {
        this.openAnchorPoint = openAnchorPoint;
    }

    public String getReferencePoint1() {
        return referencePoint1;
    }

    public void setReferencePoint1(String referencePoint1) {
        this.referencePoint1 = referencePoint1;
    }

    public String getReferencePoint2() {
        return referencePoint2;
    }

    public void setReferencePoint2(String referencePoint2) {
        this.referencePoint2 = referencePoint2;
    }

    public Integer getGeometryWidth() {
        return geometryWidth;
    }

    public void setGeometryWidth(Integer geometryWidth) {
        this.geometryWidth = geometryWidth;
    }

    public Integer getGeometryHeight() {
        return geometryHeight;
    }

    public void setGeometryHeight(Integer geometryHeight) {
        this.geometryHeight = geometryHeight;
    }

    public Integer getGeometryDepth() {
        return geometryDepth;
    }

    public void setGeometryDepth(Integer geometryDepth) {
        this.geometryDepth = geometryDepth;
    }

    public Integer getMotionDistanceBlocks() {
        return motionDistanceBlocks;
    }

    public void setMotionDistanceBlocks(Integer motionDistanceBlocks) {
        this.motionDistanceBlocks = motionDistanceBlocks;
    }

    public Boolean getClipToGeometryBounds() {
        return clipToGeometryBounds;
    }

    public void setClipToGeometryBounds(Boolean clipToGeometryBounds) {
        this.clipToGeometryBounds = clipToGeometryBounds;
    }

    public String getSeedBlocks() {
        return seedBlocks;
    }

    public void setSeedBlocks(String seedBlocks) {
        this.seedBlocks = seedBlocks;
    }

    public Integer getScanMaxBlocks() {
        return scanMaxBlocks;
    }

    public void setScanMaxBlocks(Integer scanMaxBlocks) {
        this.scanMaxBlocks = scanMaxBlocks;
    }

    public Integer getScanMaxRadius() {
        return scanMaxRadius;
    }

    public void setScanMaxRadius(Integer scanMaxRadius) {
        this.scanMaxRadius = scanMaxRadius;
    }

    public String getScanMaterialWhitelist() {
        return scanMaterialWhitelist;
    }

    public void setScanMaterialWhitelist(String scanMaterialWhitelist) {
        this.scanMaterialWhitelist = scanMaterialWhitelist;
    }

    public String getScanMaterialBlacklist() {
        return scanMaterialBlacklist;
    }

    public void setScanMaterialBlacklist(String scanMaterialBlacklist) {
        this.scanMaterialBlacklist = scanMaterialBlacklist;
    }

    public Boolean getScanPlaneConstraint() {
        return scanPlaneConstraint;
    }

    public void setScanPlaneConstraint(Boolean scanPlaneConstraint) {
        this.scanPlaneConstraint = scanPlaneConstraint;
    }

    public Integer getFallbackMaterialRefId() {
        return fallbackMaterialRefId;
    }

    public void setFallbackMaterialRefId(Integer fallbackMaterialRefId) {
        this.fallbackMaterialRefId = fallbackMaterialRefId;
    }

    public String getTileEntityPolicy() {
        return tileEntityPolicy;
    }

    public void setTileEntityPolicy(String tileEntityPolicy) {
        this.tileEntityPolicy = tileEntityPolicy;
    }

    public Integer getRotationMaxAngleDegrees() {
        return rotationMaxAngleDegrees;
    }

    public void setRotationMaxAngleDegrees(Integer rotationMaxAngleDegrees) {
        this.rotationMaxAngleDegrees = rotationMaxAngleDegrees;
    }

    public String getHingeAxis() {
        return hingeAxis;
    }

    public void setHingeAxis(String hingeAxis) {
        this.hingeAxis = hingeAxis;
    }

    public String getLeftDoorSeedBlock() {
        return leftDoorSeedBlock;
    }

    public void setLeftDoorSeedBlock(String leftDoorSeedBlock) {
        this.leftDoorSeedBlock = leftDoorSeedBlock;
    }

    public String getRightDoorSeedBlock() {
        return rightDoorSeedBlock;
    }

    public void setRightDoorSeedBlock(String rightDoorSeedBlock) {
        this.rightDoorSeedBlock = rightDoorSeedBlock;
    }

    public Boolean getMirrorRotation() {
        return mirrorRotation;
    }

    public void setMirrorRotation(Boolean mirrorRotation) {
        this.mirrorRotation = mirrorRotation;
    }

    public String getClosedRegionData() {
        return closedRegionData;
    }

    public void setClosedRegionData(String closedRegionData) {
        this.closedRegionData = closedRegionData;
    }

    public String getOpenedRegionData() {
        return openedRegionData;
    }

    public void setOpenedRegionData(String openedRegionData) {
        this.openedRegionData = openedRegionData;
    }

    public Boolean getAllowPassThrough() {
        return allowPassThrough;
    }

    public void setAllowPassThrough(Boolean allowPassThrough) {
        this.allowPassThrough = allowPassThrough;
    }

    public Integer getPassThroughDurationSeconds() {
        return passThroughDurationSeconds;
    }

    public void setPassThroughDurationSeconds(Integer passThroughDurationSeconds) {
        this.passThroughDurationSeconds = passThroughDurationSeconds;
    }

    public String getInfoDisplayLocation() {
        return infoDisplayLocation;
    }

    public void setInfoDisplayLocation(String infoDisplayLocation) {
        this.infoDisplayLocation = infoDisplayLocation;
    }

    public Boolean getShowHealthDisplay() {
        return showHealthDisplay;
    }

    public void setShowHealthDisplay(Boolean showHealthDisplay) {
        this.showHealthDisplay = showHealthDisplay;
    }

    public String getHealthDisplayMode() {
        return healthDisplayMode;
    }

    public void setHealthDisplayMode(String healthDisplayMode) {
        this.healthDisplayMode = healthDisplayMode;
    }

    public Integer getHealthDisplayYOffset() {
        return healthDisplayYOffset;
    }

    public void setHealthDisplayYOffset(Integer healthDisplayYOffset) {
        this.healthDisplayYOffset = healthDisplayYOffset;
    }

    public String getGateNameDisplayMode() {
        return gateNameDisplayMode;
    }

    public void setGateNameDisplayMode(String gateNameDisplayMode) {
        this.gateNameDisplayMode = gateNameDisplayMode;
    }

    public String getStatusDisplayMode() {
        return statusDisplayMode;
    }

    public void setStatusDisplayMode(String statusDisplayMode) {
        this.statusDisplayMode = statusDisplayMode;
    }

    public String getDoorNameDisplayMode() {
        return doorNameDisplayMode;
    }

    public void setDoorNameDisplayMode(String doorNameDisplayMode) {
        this.doorNameDisplayMode = doorNameDisplayMode;
    }

    public Boolean getAllowContinuousDamage() {
        return allowContinuousDamage;
    }

    public void setAllowContinuousDamage(Boolean allowContinuousDamage) {
        this.allowContinuousDamage = allowContinuousDamage;
    }

    public Double getContinuousDamageMultiplier() {
        return continuousDamageMultiplier;
    }

    public void setContinuousDamageMultiplier(Double continuousDamageMultiplier) {
        this.continuousDamageMultiplier = continuousDamageMultiplier;
    }

    public Integer getContinuousDamageDurationSeconds() {
        return continuousDamageDurationSeconds;
    }

    public void setContinuousDamageDurationSeconds(Integer continuousDamageDurationSeconds) {
        this.continuousDamageDurationSeconds = continuousDamageDurationSeconds;
    }

    public List<GateBlockSnapshotDto> getBlockSnapshots() {
        return blockSnapshots;
    }

    public void setBlockSnapshots(List<GateBlockSnapshotDto> blockSnapshots) {
        this.blockSnapshots = blockSnapshots;
    }

    public List<GateBlockSnapshotDto> getOpenedBlockSnapshots() {
        return openedBlockSnapshots;
    }

    public void setOpenedBlockSnapshots(List<GateBlockSnapshotDto> openedBlockSnapshots) {
        this.openedBlockSnapshots = openedBlockSnapshots;
    }
}
