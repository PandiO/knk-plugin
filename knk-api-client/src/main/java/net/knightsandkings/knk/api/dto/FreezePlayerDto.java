package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to knk-web-api's FreezePlayerDto - PUT /api/users/{id}/freeze.
 */
public record FreezePlayerDto(
    @JsonProperty("reason") String reason
) {}
