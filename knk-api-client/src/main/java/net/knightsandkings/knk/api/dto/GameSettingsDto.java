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
        @JsonProperty("groupOverrides") List<GroupOverride> groupOverrides,
        @JsonProperty("hub") Hub hub
) {
    /** Without the hub block (KNG-52 shape). */
    public GameSettingsDto(String joinSpawnMode, LocationReference joinSpawnReference, String joinAnnouncement,
                           String leaveAnnouncement, RespawnPolicy defaultRespawnPolicy, List<WorldSettings> worldSettings,
                           List<RuntimeWorld> runtimeWorlds, String updatedAt, String motd, List<GroupOverride> groupOverrides) {
        this(joinSpawnMode, joinSpawnReference, joinAnnouncement, leaveAnnouncement, defaultRespawnPolicy, worldSettings,
            runtimeWorlds, updatedAt, motd, groupOverrides, null);
    }

    /** knk-web-api {@code HubSettingsDto} (hub DESIGN §16 Phase 4). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hub(
            @JsonProperty("domainId") Integer domainId,
            @JsonProperty("domainName") String domainName,
            @JsonProperty("domainType") String domainType,
            @JsonProperty("worldName") String worldName,
            @JsonProperty("wgRegionId") String wgRegionId,
            @JsonProperty("spawnReference") LocationReference spawnReference,
            @JsonProperty("outageAction") String outageAction,
            @JsonProperty("outageDelaySeconds") Integer outageDelaySeconds,
            @JsonProperty("rules") HubRules rules,
            @JsonProperty("portalRegionIds") List<String> portalRegionIds
    ) {}

    /** knk-web-api {@code HubRulesDto}; a missing field keeps the hub default. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HubRules(
            @JsonProperty("damagePlayers") Boolean damagePlayers,
            @JsonProperty("damageEntities") Boolean damageEntities,
            @JsonProperty("takeDamage") Boolean takeDamage,
            @JsonProperty("buildBreak") Boolean buildBreak,
            @JsonProperty("dropPickup") Boolean dropPickup,
            @JsonProperty("useDoors") Boolean useDoors,
            @JsonProperty("useNpcs") Boolean useNpcs,
            @JsonProperty("useStorage") Boolean useStorage,
            @JsonProperty("useItemFramesPaintings") Boolean useItemFramesPaintings,
            @JsonProperty("healAmount") Double healAmount,
            @JsonProperty("healIntervalTicks") Integer healIntervalTicks,
            @JsonProperty("feedAmount") Integer feedAmount,
            @JsonProperty("feedIntervalTicks") Integer feedIntervalTicks,
            @JsonProperty("saturationAmount") Double saturationAmount,
            @JsonProperty("saturationIntervalTicks") Integer saturationIntervalTicks
    ) {}

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
            @JsonProperty("joinAtLastLocation") Boolean joinAtLastLocation,
            @JsonProperty("outageAction") String outageAction,
            @JsonProperty("outageDelaySeconds") Integer outageDelaySeconds
    ) {
        /** Without the hub's outage fields (KNG-52 shape). */
        public GroupOverride(int permissionGroupId, String groupName, Integer precedence, String joinAnnouncement,
                             String leaveAnnouncement, LocationReference joinSpawnReference, RespawnPolicy respawnPolicy,
                             Boolean joinAtLastLocation) {
            this(permissionGroupId, groupName, precedence, joinAnnouncement, leaveAnnouncement, joinSpawnReference,
                respawnPolicy, joinAtLastLocation, null, null);
        }
    }

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
