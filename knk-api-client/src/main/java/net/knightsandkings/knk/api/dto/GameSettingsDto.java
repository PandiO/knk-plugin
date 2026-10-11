package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code GET /api/GameSettings} (knk-web-api {@code GameSettingsReadDto}), also the response of
 * {@code PUT /api/GameSettings/runtime-worlds}. Timestamps stay strings: the API writes them without
 * an offset. Boxed numbers so a missing field is null, not 0.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GameSettingsDto(
        @JsonProperty("joinSpawnMode") String joinSpawnMode,
        @JsonProperty("joinSpawnReference") LocationReference joinSpawnReference,
        @JsonProperty("joinAnnouncement") String joinAnnouncement,
        @JsonProperty("leaveAnnouncement") String leaveAnnouncement,
        @JsonProperty("defaultRespawnPolicy") RespawnPolicy defaultRespawnPolicy,
        @JsonProperty("worldSettings") List<WorldSettings> worldSettings,
        @JsonProperty("runtimeWorlds") List<RuntimeWorld> runtimeWorlds,
        @JsonProperty("updatedAt") String updatedAt,
        @JsonProperty("motd") String motd,
        @JsonProperty("groupOverrides") List<GroupOverride> groupOverrides
) {
    /** knk-web-api {@code PermissionGroupGameSettingsDto} (KNG-52). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GroupOverride(
            @JsonProperty("permissionGroupId") int permissionGroupId,
            @JsonProperty("groupName") String groupName,
            @JsonProperty("precedence") Integer precedence,
            @JsonProperty("joinAnnouncement") String joinAnnouncement,
            @JsonProperty("leaveAnnouncement") String leaveAnnouncement,
            @JsonProperty("joinSpawnReference") LocationReference joinSpawnReference,
            @JsonProperty("respawnPolicy") RespawnPolicy respawnPolicy,
            @JsonProperty("joinAtLastLocation") Boolean joinAtLastLocation
    ) {}

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

    /** knk-web-api {@code RespawnPolicyDto}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RespawnPolicy(
            @JsonProperty("mode") String mode,
            @JsonProperty("locationReference") LocationReference locationReference,
            @JsonProperty("maxNearestTownDistance") Double maxNearestTownDistance,
            @JsonProperty("useWorldSpawnFallback") Boolean useWorldSpawnFallback
    ) {}

    /** knk-web-api {@code WorldWeatherSettingsDto}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WeatherSettings(
            @JsonProperty("mode") String mode,
            @JsonProperty("forcedWeather") String forcedWeather,
            @JsonProperty("blockedWeatherTypes") List<String> blockedWeatherTypes,
            @JsonProperty("clearWeight") Integer clearWeight,
            @JsonProperty("rainWeight") Integer rainWeight,
            @JsonProperty("thunderWeight") Integer thunderWeight
    ) {}

    /** knk-web-api {@code WorldGameSettingsDto}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorldSettings(
            @JsonProperty("worldName") String worldName,
            @JsonProperty("worldFolderName") String worldFolderName,
            @JsonProperty("defaultGameMode") String defaultGameMode,
            @JsonProperty("lockTime") Boolean lockTime,
            @JsonProperty("lockedTime") Long lockedTime,
            @JsonProperty("weather") WeatherSettings weather,
            @JsonProperty("worldSpawnReference") LocationReference worldSpawnReference,
            @JsonProperty("respawnPolicy") RespawnPolicy respawnPolicy
    ) {}

    /** knk-web-api {@code MinecraftWorldRuntimeDto}; also the body items of the runtime-worlds PUT. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RuntimeWorld(
            @JsonProperty("worldName") String worldName,
            @JsonProperty("folderName") String folderName,
            @JsonProperty("environment") String environment,
            @JsonProperty("loaded") boolean loaded,
            @JsonProperty("playerCount") int playerCount,
            @JsonProperty("isPrimary") boolean isPrimary
    ) {}

    /** {@code PUT /api/GameSettings/runtime-worlds} body (knk-web-api {@code GameSettingsRuntimeWorldsUpdateDto}). */
    public record RuntimeWorldsUpdate(@JsonProperty("runtimeWorlds") List<RuntimeWorld> runtimeWorlds) {}
}
