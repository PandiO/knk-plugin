package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for updating a user's preferred gate pass-through method.
 * Maps to UpdateGatePassThroughMethodDto from knk-web-api - the wire value is the backend's
 * PascalCase enum name (e.g. "InstantOpen"), see GatePassThroughMethod.toWireValue().
 */
public record GatePassThroughMethodUpdateDto(
    @JsonProperty("gatePassThroughMethodDefault") String gatePassThroughMethodDefault
) {}
