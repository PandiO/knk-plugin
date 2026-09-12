package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for PATCH /api/GateStructures/{id}/overrides - sets/clears the structure-level
 * cascading overrides (decision 5.0-B, docs/features/gate-structure-animation/
 * GATESTRUCTURE_QOL_IMPLEMENTATION_PLAN.md item 5). A field left null is NOT changed; to clear an
 * override explicitly, set its matching "clear" flag instead, since "field absent" and "field
 * explicitly cleared" both serialize the same way as null.
 */
public class GateStructureOverridesUpdateDto {

    @JsonProperty("isActiveOverride")
    private Boolean isActiveOverride;
    @JsonProperty("clearIsActiveOverride")
    private boolean clearIsActiveOverride;

    @JsonProperty("canRespawnOverride")
    private Boolean canRespawnOverride;
    @JsonProperty("clearCanRespawnOverride")
    private boolean clearCanRespawnOverride;

    @JsonProperty("isDestroyedOverride")
    private Boolean isDestroyedOverride;
    @JsonProperty("clearIsDestroyedOverride")
    private boolean clearIsDestroyedOverride;

    @JsonProperty("isInvincibleOverride")
    private Boolean isInvincibleOverride;
    @JsonProperty("clearIsInvincibleOverride")
    private boolean clearIsInvincibleOverride;

    @JsonProperty("openedStateOverride")
    private String openedStateOverride;
    @JsonProperty("clearOpenedStateOverride")
    private boolean clearOpenedStateOverride;

    @JsonProperty("allowPassThroughOverride")
    private Boolean allowPassThroughOverride;
    @JsonProperty("clearAllowPassThroughOverride")
    private boolean clearAllowPassThroughOverride;

    @JsonProperty("passThroughDurationSecondsOverride")
    private Integer passThroughDurationSecondsOverride;
    @JsonProperty("clearPassThroughDurationSecondsOverride")
    private boolean clearPassThroughDurationSecondsOverride;

    @JsonProperty("showHealthDisplayOverride")
    private Boolean showHealthDisplayOverride;
    @JsonProperty("clearShowHealthDisplayOverride")
    private boolean clearShowHealthDisplayOverride;

    @JsonProperty("healthDisplayModeOverride")
    private String healthDisplayModeOverride;
    @JsonProperty("clearHealthDisplayModeOverride")
    private boolean clearHealthDisplayModeOverride;

    @JsonProperty("healthDisplayYOffsetOverride")
    private Integer healthDisplayYOffsetOverride;
    @JsonProperty("clearHealthDisplayYOffsetOverride")
    private boolean clearHealthDisplayYOffsetOverride;

    @JsonProperty("gateNameDisplayModeOverride")
    private String gateNameDisplayModeOverride;
    @JsonProperty("clearGateNameDisplayModeOverride")
    private boolean clearGateNameDisplayModeOverride;

    @JsonProperty("statusDisplayModeOverride")
    private String statusDisplayModeOverride;
    @JsonProperty("clearStatusDisplayModeOverride")
    private boolean clearStatusDisplayModeOverride;

    @JsonProperty("allowContinuousDamageOverride")
    private Boolean allowContinuousDamageOverride;
    @JsonProperty("clearAllowContinuousDamageOverride")
    private boolean clearAllowContinuousDamageOverride;

    @JsonProperty("continuousDamageMultiplierOverride")
    private Double continuousDamageMultiplierOverride;
    @JsonProperty("clearContinuousDamageMultiplierOverride")
    private boolean clearContinuousDamageMultiplierOverride;

    public Boolean getIsActiveOverride() {
        return isActiveOverride;
    }

    public void setIsActiveOverride(Boolean isActiveOverride) {
        this.isActiveOverride = isActiveOverride;
    }

    public boolean isClearIsActiveOverride() {
        return clearIsActiveOverride;
    }

    public void setClearIsActiveOverride(boolean clearIsActiveOverride) {
        this.clearIsActiveOverride = clearIsActiveOverride;
    }

    public Boolean getCanRespawnOverride() {
        return canRespawnOverride;
    }

    public void setCanRespawnOverride(Boolean canRespawnOverride) {
        this.canRespawnOverride = canRespawnOverride;
    }

    public boolean isClearCanRespawnOverride() {
        return clearCanRespawnOverride;
    }

    public void setClearCanRespawnOverride(boolean clearCanRespawnOverride) {
        this.clearCanRespawnOverride = clearCanRespawnOverride;
    }

    public Boolean getIsDestroyedOverride() {
        return isDestroyedOverride;
    }

    public void setIsDestroyedOverride(Boolean isDestroyedOverride) {
        this.isDestroyedOverride = isDestroyedOverride;
    }

    public boolean isClearIsDestroyedOverride() {
        return clearIsDestroyedOverride;
    }

    public void setClearIsDestroyedOverride(boolean clearIsDestroyedOverride) {
        this.clearIsDestroyedOverride = clearIsDestroyedOverride;
    }

    public Boolean getIsInvincibleOverride() {
        return isInvincibleOverride;
    }

    public void setIsInvincibleOverride(Boolean isInvincibleOverride) {
        this.isInvincibleOverride = isInvincibleOverride;
    }

    public boolean isClearIsInvincibleOverride() {
        return clearIsInvincibleOverride;
    }

    public void setClearIsInvincibleOverride(boolean clearIsInvincibleOverride) {
        this.clearIsInvincibleOverride = clearIsInvincibleOverride;
    }

    public String getOpenedStateOverride() {
        return openedStateOverride;
    }

    public void setOpenedStateOverride(String openedStateOverride) {
        this.openedStateOverride = openedStateOverride;
    }

    public boolean isClearOpenedStateOverride() {
        return clearOpenedStateOverride;
    }

    public void setClearOpenedStateOverride(boolean clearOpenedStateOverride) {
        this.clearOpenedStateOverride = clearOpenedStateOverride;
    }

    public Boolean getAllowPassThroughOverride() {
        return allowPassThroughOverride;
    }

    public void setAllowPassThroughOverride(Boolean allowPassThroughOverride) {
        this.allowPassThroughOverride = allowPassThroughOverride;
    }

    public boolean isClearAllowPassThroughOverride() {
        return clearAllowPassThroughOverride;
    }

    public void setClearAllowPassThroughOverride(boolean clearAllowPassThroughOverride) {
        this.clearAllowPassThroughOverride = clearAllowPassThroughOverride;
    }

    public Integer getPassThroughDurationSecondsOverride() {
        return passThroughDurationSecondsOverride;
    }

    public void setPassThroughDurationSecondsOverride(Integer passThroughDurationSecondsOverride) {
        this.passThroughDurationSecondsOverride = passThroughDurationSecondsOverride;
    }

    public boolean isClearPassThroughDurationSecondsOverride() {
        return clearPassThroughDurationSecondsOverride;
    }

    public void setClearPassThroughDurationSecondsOverride(boolean clearPassThroughDurationSecondsOverride) {
        this.clearPassThroughDurationSecondsOverride = clearPassThroughDurationSecondsOverride;
    }

    public Boolean getShowHealthDisplayOverride() {
        return showHealthDisplayOverride;
    }

    public void setShowHealthDisplayOverride(Boolean showHealthDisplayOverride) {
        this.showHealthDisplayOverride = showHealthDisplayOverride;
    }

    public boolean isClearShowHealthDisplayOverride() {
        return clearShowHealthDisplayOverride;
    }

    public void setClearShowHealthDisplayOverride(boolean clearShowHealthDisplayOverride) {
        this.clearShowHealthDisplayOverride = clearShowHealthDisplayOverride;
    }

    public String getHealthDisplayModeOverride() {
        return healthDisplayModeOverride;
    }

    public void setHealthDisplayModeOverride(String healthDisplayModeOverride) {
        this.healthDisplayModeOverride = healthDisplayModeOverride;
    }

    public boolean isClearHealthDisplayModeOverride() {
        return clearHealthDisplayModeOverride;
    }

    public void setClearHealthDisplayModeOverride(boolean clearHealthDisplayModeOverride) {
        this.clearHealthDisplayModeOverride = clearHealthDisplayModeOverride;
    }

    public Integer getHealthDisplayYOffsetOverride() {
        return healthDisplayYOffsetOverride;
    }

    public void setHealthDisplayYOffsetOverride(Integer healthDisplayYOffsetOverride) {
        this.healthDisplayYOffsetOverride = healthDisplayYOffsetOverride;
    }

    public boolean isClearHealthDisplayYOffsetOverride() {
        return clearHealthDisplayYOffsetOverride;
    }

    public void setClearHealthDisplayYOffsetOverride(boolean clearHealthDisplayYOffsetOverride) {
        this.clearHealthDisplayYOffsetOverride = clearHealthDisplayYOffsetOverride;
    }

    public String getGateNameDisplayModeOverride() {
        return gateNameDisplayModeOverride;
    }

    public void setGateNameDisplayModeOverride(String gateNameDisplayModeOverride) {
        this.gateNameDisplayModeOverride = gateNameDisplayModeOverride;
    }

    public boolean isClearGateNameDisplayModeOverride() {
        return clearGateNameDisplayModeOverride;
    }

    public void setClearGateNameDisplayModeOverride(boolean clearGateNameDisplayModeOverride) {
        this.clearGateNameDisplayModeOverride = clearGateNameDisplayModeOverride;
    }

    public String getStatusDisplayModeOverride() {
        return statusDisplayModeOverride;
    }

    public void setStatusDisplayModeOverride(String statusDisplayModeOverride) {
        this.statusDisplayModeOverride = statusDisplayModeOverride;
    }

    public boolean isClearStatusDisplayModeOverride() {
        return clearStatusDisplayModeOverride;
    }

    public void setClearStatusDisplayModeOverride(boolean clearStatusDisplayModeOverride) {
        this.clearStatusDisplayModeOverride = clearStatusDisplayModeOverride;
    }

    public Boolean getAllowContinuousDamageOverride() {
        return allowContinuousDamageOverride;
    }

    public void setAllowContinuousDamageOverride(Boolean allowContinuousDamageOverride) {
        this.allowContinuousDamageOverride = allowContinuousDamageOverride;
    }

    public boolean isClearAllowContinuousDamageOverride() {
        return clearAllowContinuousDamageOverride;
    }

    public void setClearAllowContinuousDamageOverride(boolean clearAllowContinuousDamageOverride) {
        this.clearAllowContinuousDamageOverride = clearAllowContinuousDamageOverride;
    }

    public Double getContinuousDamageMultiplierOverride() {
        return continuousDamageMultiplierOverride;
    }

    public void setContinuousDamageMultiplierOverride(Double continuousDamageMultiplierOverride) {
        this.continuousDamageMultiplierOverride = continuousDamageMultiplierOverride;
    }

    public boolean isClearContinuousDamageMultiplierOverride() {
        return clearContinuousDamageMultiplierOverride;
    }

    public void setClearContinuousDamageMultiplierOverride(boolean clearContinuousDamageMultiplierOverride) {
        this.clearContinuousDamageMultiplierOverride = clearContinuousDamageMultiplierOverride;
    }
}
