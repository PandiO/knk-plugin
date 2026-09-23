package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for updating a user's owner/staff mode.
 * Maps to UpdateActiveModeDto from knk-web-api - the wire value is the backend's PascalCase enum
 * name (e.g. "Owner"), see ActiveMode.toWireValue().
 */
public record ActiveModeUpdateDto(
    @JsonProperty("activeMode") String activeMode
) {}
