package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to knk-web-api's NavigationCompassSettingsDto (KNG-234): the item rule (material + display name
 * match), where the compass works, and the destinations it cycles outside a siege (one per line).
 * {@code nameMatch} is the enum name, "Exact" or "Contains".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NavigationCompassSettingsDto(
    @JsonProperty("enabled") boolean enabled,
    @JsonProperty("material") String material,
    @JsonProperty("displayName") String displayName,
    @JsonProperty("nameMatch") String nameMatch,
    @JsonProperty("siegeEnabled") boolean siegeEnabled,
    @JsonProperty("generalEnabled") boolean generalEnabled,
    @JsonProperty("generalTargets") String generalTargets
) {}
