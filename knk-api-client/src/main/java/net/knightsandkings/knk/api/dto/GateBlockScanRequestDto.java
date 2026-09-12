package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * InputJson payload for a GateBlockScan WorldTask. A scan targets one door, not a whole
 * structure, since item 5's multi-door support - full door geometry is re-fetched via
 * GateDoorsApi.getById(gateDoorId).
 */
public record GateBlockScanRequestDto(
    @JsonProperty("gateDoorId") Integer gateDoorId
) {
}
