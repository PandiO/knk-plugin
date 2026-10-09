package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The body the road controllers send on a refusal (knk-web-api's RoadControllerBase):
 * {@code error} is {@code ValidationFailed} (400), {@code NotFound} (404) or {@code Conflict} (409).
 */
public record RoadErrorDto(
    @JsonProperty("error") String error,
    @JsonProperty("message") String message
) {}
