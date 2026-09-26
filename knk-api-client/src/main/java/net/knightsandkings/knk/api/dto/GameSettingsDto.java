package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code GET /api/GameSettings} (knk-web-api {@code GameSettingsReadDto}) - only the fields the plugin
 * reads; the rest (announcements, world settings, runtime worlds...) is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GameSettingsDto(
        @JsonProperty("joinSpawnMode") String joinSpawnMode,
        @JsonProperty("joinSpawnReference") LocationReference joinSpawnReference
) {
    /** knk-web-api {@code LocationReferenceDto}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LocationReference(
            @JsonProperty("sourceType") String sourceType,
            @JsonProperty("sourceId") Integer sourceId,
            @JsonProperty("displayLabel") String displayLabel,
            @JsonProperty("location") LocationSnapshot location
    ) {}

    /** knk-web-api {@code LocationSnapshotDto}: the coordinates as they were when the reference was saved. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LocationSnapshot(
            @JsonProperty("locationId") Integer locationId,
            @JsonProperty("name") String name,
            @JsonProperty("x") Double x,
            @JsonProperty("y") Double y,
            @JsonProperty("z") Double z,
            @JsonProperty("yaw") Float yaw,
            @JsonProperty("pitch") Float pitch,
            @JsonProperty("world") String world
    ) {}
}
