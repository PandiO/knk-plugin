package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for gate block snapshot from Web API (read path: GET /GateDoors/{id}/snapshots). Field
 * names must match the C# GateBlockSnapshotDto contract exactly, or fields silently deserialize
 * to null/0 (the client's ObjectMapper has FAIL_ON_UNKNOWN_PROPERTIES disabled). Item 5
 * (docs/features/gate-structure-animation/GATESTRUCTURE_QOL_IMPLEMENTATION_PLAN.md) moved
 * snapshot ownership from GateStructure to GateDoor.
 */
public record GateBlockSnapshotDto(
    @JsonProperty("id") Integer id,
    @JsonProperty("gateDoorId") Integer gateDoorId,
    @JsonProperty("relativeX") Integer relativeX,
    @JsonProperty("relativeY") Integer relativeY,
    @JsonProperty("relativeZ") Integer relativeZ,
    @JsonProperty("worldX") Integer worldX,
    @JsonProperty("worldY") Integer worldY,
    @JsonProperty("worldZ") Integer worldZ,
    @JsonProperty("materialName") String materialName,
    @JsonProperty("blockDataJson") String blockDataJson,
    @JsonProperty("tileEntityJson") String tileEntityJson,
    @JsonProperty("sortOrder") Integer sortOrder
) {}
