package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;

/**
 * DTO for gate structure details from Web API.
 * Maps to GateStructureDto from knk-web-api.
 *
 * Item 5 (docs/features/gate-structure-animation/GATESTRUCTURE_QOL_IMPLEMENTATION_PLAN.md) moved
 * every per-door field (geometry, animation, health, block snapshots, etc.) onto the new
 * GateDoorDto, embedded here as {@link #gateDoors}. What's left here is structure-level identity,
 * the guard/siege systems, and the structure-level cascading override fields (decision 5.0-B).
 */
public class GateStructureDto {

    // === Core Identity ===
    @JsonProperty("id")
    private Integer id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("districtId")
    private Integer districtId;

    @JsonProperty("streetId")
    private Integer streetId;

    // === Guard & Defense System (Future Feature) ===
    @JsonProperty("guardCount")
    private Integer guardCount;

    @JsonProperty("guardNpcTemplateId")
    private Integer guardNpcTemplateId;

    // === Siege Integration ===
    @JsonProperty("isOverridable")
    private Boolean isOverridable;

    @JsonProperty("animateDuringSiege")
    private Boolean animateDuringSiege;

    @JsonProperty("currentSiegeId")
    private Integer currentSiegeId;

    @JsonProperty("isSiegeObjective")
    private Boolean isSiegeObjective;

    // === Structure-level cascading overrides (decision 5.0-B) ===
    // Null means "no override, each door uses its own value". Set/cleared via
    // PATCH /api/GateStructures/{id}/overrides (see GateDoorsApi/updateOverrides), not via this
    // general read DTO.
    @JsonProperty("isActiveOverride")
    private Boolean isActiveOverride;

    @JsonProperty("canRespawnOverride")
    private Boolean canRespawnOverride;

    @JsonProperty("isDestroyedOverride")
    private Boolean isDestroyedOverride;

    @JsonProperty("isInvincibleOverride")
    private Boolean isInvincibleOverride;

    @JsonProperty("openedStateOverride")
    private String openedStateOverride;

    @JsonProperty("allowPassThroughOverride")
    private Boolean allowPassThroughOverride;

    @JsonProperty("passThroughDurationSecondsOverride")
    private Integer passThroughDurationSecondsOverride;

    @JsonProperty("showHealthDisplayOverride")
    private Boolean showHealthDisplayOverride;

    @JsonProperty("healthDisplayModeOverride")
    private String healthDisplayModeOverride;

    @JsonProperty("healthDisplayYOffsetOverride")
    private Integer healthDisplayYOffsetOverride;

    @JsonProperty("gateNameDisplayModeOverride")
    private String gateNameDisplayModeOverride;

    @JsonProperty("statusDisplayModeOverride")
    private String statusDisplayModeOverride;

    @JsonProperty("allowContinuousDamageOverride")
    private Boolean allowContinuousDamageOverride;

    @JsonProperty("continuousDamageMultiplierOverride")
    private Double continuousDamageMultiplierOverride;

    // === Navigation: this structure's doors (item 5) ===
    @JsonProperty("gateDoors")
    private List<GateDoorDto> gateDoors;

    // === Getters and Setters ===

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getDistrictId() {
        return districtId;
    }

    public void setDistrictId(Integer districtId) {
        this.districtId = districtId;
    }

    public Integer getStreetId() {
        return streetId;
    }

    public void setStreetId(Integer streetId) {
        this.streetId = streetId;
    }

    public Integer getGuardCount() {
        return guardCount;
    }

    public void setGuardCount(Integer guardCount) {
        this.guardCount = guardCount;
    }

    public Integer getGuardNpcTemplateId() {
        return guardNpcTemplateId;
    }

    public void setGuardNpcTemplateId(Integer guardNpcTemplateId) {
        this.guardNpcTemplateId = guardNpcTemplateId;
    }

    public Boolean getIsOverridable() {
        return isOverridable;
    }

    public void setIsOverridable(Boolean isOverridable) {
        this.isOverridable = isOverridable;
    }

    public Boolean getAnimateDuringSiege() {
        return animateDuringSiege;
    }

    public void setAnimateDuringSiege(Boolean animateDuringSiege) {
        this.animateDuringSiege = animateDuringSiege;
    }

    public Integer getCurrentSiegeId() {
        return currentSiegeId;
    }

    public void setCurrentSiegeId(Integer currentSiegeId) {
        this.currentSiegeId = currentSiegeId;
    }

    public Boolean getIsSiegeObjective() {
        return isSiegeObjective;
    }

    public void setIsSiegeObjective(Boolean isSiegeObjective) {
        this.isSiegeObjective = isSiegeObjective;
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

    public List<GateDoorDto> getGateDoors() {
        return gateDoors;
    }

    public void setGateDoors(List<GateDoorDto> gateDoors) {
        this.gateDoors = gateDoors;
    }

    /** Shared by GateDoorDto's own point fields - deserializes either a plain coordinate string
     *  or (today's wire shape) a LocationDto object, verbatim as its raw JSON text either way. */
    public static class CoordinateStringDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode value = parser.readValueAsTree();
            return value.isTextual() ? value.asText() : value.toString();
        }
    }
}
