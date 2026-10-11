package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to knk-web-api's RoadMaterialDto (road navigation, KNG-27). {@code role} is the enum name
 * ({@code Surface}, {@code Edge}, {@code Accent}, {@code Overlay}).
 */
public record RoadMaterialDto(
    @JsonProperty("material") String material,
    @JsonProperty("role") String role,
    @JsonProperty("ambiguous") boolean ambiguous,
    @JsonProperty("centreShare") double centreShare,
    @JsonProperty("edgeShare") double edgeShare,
    @JsonProperty("samples") int samples
) {}
