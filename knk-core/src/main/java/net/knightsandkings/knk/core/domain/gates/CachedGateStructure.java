package net.knightsandkings.knk.core.domain.gates;

/**
 * Cached representation of a gate structure's structure-level data: identity, the guard/siege
 * systems, and the structure-level cascading override fields (decision 5.0-B,
 * docs/features/gate-structure-animation/GATESTRUCTURE_QOL_IMPLEMENTATION_PLAN.md item 5).
 *
 * <p>Per-door data (geometry, animation, health, block snapshots) lives on {@link CachedGateDoor},
 * which holds a back-pointer to its parent structure so its {@code isEffectivelyXxx()}/
 * {@code getEffectiveXxx()} accessors can resolve {@code structureOverride ?? door.ownValue} at
 * read time - mirroring the backend's {@code GateDoor.GetEffective} helper, without needing a
 * per-door write when an override is set or cleared (decision B: "resolved at read time, not a
 * write-cascade").
 */
public class CachedGateStructure {
    private final int id;
    private final String name;

    // === Siege Integration (whole-structure event, cascades to every door) ===
    private Integer currentSiegeId;
    private boolean isOverridable = true;
    private boolean animateDuringSiege = true;
    private boolean isSiegeObjective = false;

    // === Structure-level cascading overrides (decision 5.0-B) ===
    // Null means "no override, each door uses its own value".
    private Boolean isActiveOverride;
    private Boolean canRespawnOverride;
    private Boolean isDestroyedOverride;
    private Boolean isInvincibleOverride;
    // One of CLOSED/OPENING/OPEN/CLOSING/JAMMED, or null for no override.
    private String openedStateOverride;
    private Boolean allowPassThroughOverride;
    private Integer passThroughDurationSecondsOverride;
    private Boolean showHealthDisplayOverride;
    private String healthDisplayModeOverride;
    private Integer healthDisplayYOffsetOverride;
    private String gateNameDisplayModeOverride;
    private String statusDisplayModeOverride;
    private Boolean allowContinuousDamageOverride;
    private Double continuousDamageMultiplierOverride;

    public CachedGateStructure(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Integer getCurrentSiegeId() {
        return currentSiegeId;
    }

    public void setCurrentSiegeId(Integer currentSiegeId) {
        this.currentSiegeId = currentSiegeId;
    }

    public boolean isOverridable() {
        return isOverridable;
    }

    public void setOverridable(boolean overridable) {
        isOverridable = overridable;
    }

    public boolean isAnimateDuringSiege() {
        return animateDuringSiege;
    }

    public void setAnimateDuringSiege(boolean animateDuringSiege) {
        this.animateDuringSiege = animateDuringSiege;
    }

    public boolean isSiegeObjective() {
        return isSiegeObjective;
    }

    public void setSiegeObjective(boolean siegeObjective) {
        isSiegeObjective = siegeObjective;
    }

    public Boolean getIsActiveOverride() {
        return isActiveOverride;
    }

    public void setIsActiveOverride(Boolean isActiveOverride) {
        this.isActiveOverride = isActiveOverride;
    }

    public Boolean getCanRespawnOverride() {
        return canRespawnOverride;
    }

    public void setCanRespawnOverride(Boolean canRespawnOverride) {
        this.canRespawnOverride = canRespawnOverride;
    }

    public Boolean getIsDestroyedOverride() {
        return isDestroyedOverride;
    }

    public void setIsDestroyedOverride(Boolean isDestroyedOverride) {
        this.isDestroyedOverride = isDestroyedOverride;
    }

    public Boolean getIsInvincibleOverride() {
        return isInvincibleOverride;
    }

    public void setIsInvincibleOverride(Boolean isInvincibleOverride) {
        this.isInvincibleOverride = isInvincibleOverride;
    }

    public String getOpenedStateOverride() {
        return openedStateOverride;
    }

    public void setOpenedStateOverride(String openedStateOverride) {
        this.openedStateOverride = openedStateOverride;
    }

    public Boolean getAllowPassThroughOverride() {
        return allowPassThroughOverride;
    }

    public void setAllowPassThroughOverride(Boolean allowPassThroughOverride) {
        this.allowPassThroughOverride = allowPassThroughOverride;
    }

    public Integer getPassThroughDurationSecondsOverride() {
        return passThroughDurationSecondsOverride;
    }

    public void setPassThroughDurationSecondsOverride(Integer passThroughDurationSecondsOverride) {
        this.passThroughDurationSecondsOverride = passThroughDurationSecondsOverride;
    }

    public Boolean getShowHealthDisplayOverride() {
        return showHealthDisplayOverride;
    }

    public void setShowHealthDisplayOverride(Boolean showHealthDisplayOverride) {
        this.showHealthDisplayOverride = showHealthDisplayOverride;
    }

    public String getHealthDisplayModeOverride() {
        return healthDisplayModeOverride;
    }

    public void setHealthDisplayModeOverride(String healthDisplayModeOverride) {
        this.healthDisplayModeOverride = healthDisplayModeOverride;
    }

    public Integer getHealthDisplayYOffsetOverride() {
        return healthDisplayYOffsetOverride;
    }

    public void setHealthDisplayYOffsetOverride(Integer healthDisplayYOffsetOverride) {
        this.healthDisplayYOffsetOverride = healthDisplayYOffsetOverride;
    }

    public String getGateNameDisplayModeOverride() {
        return gateNameDisplayModeOverride;
    }

    public void setGateNameDisplayModeOverride(String gateNameDisplayModeOverride) {
        this.gateNameDisplayModeOverride = gateNameDisplayModeOverride;
    }

    public String getStatusDisplayModeOverride() {
        return statusDisplayModeOverride;
    }

    public void setStatusDisplayModeOverride(String statusDisplayModeOverride) {
        this.statusDisplayModeOverride = statusDisplayModeOverride;
    }

    public Boolean getAllowContinuousDamageOverride() {
        return allowContinuousDamageOverride;
    }

    public void setAllowContinuousDamageOverride(Boolean allowContinuousDamageOverride) {
        this.allowContinuousDamageOverride = allowContinuousDamageOverride;
    }

    public Double getContinuousDamageMultiplierOverride() {
        return continuousDamageMultiplierOverride;
    }

    public void setContinuousDamageMultiplierOverride(Double continuousDamageMultiplierOverride) {
        this.continuousDamageMultiplierOverride = continuousDamageMultiplierOverride;
    }
}
