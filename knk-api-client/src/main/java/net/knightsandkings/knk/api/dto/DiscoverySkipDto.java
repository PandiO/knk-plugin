package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to knk-web-api's DiscoverySkipDto. */
public record DiscoverySkipDto(
    @JsonProperty("key") String key,
    @JsonProperty("reason") String reason
) {}
