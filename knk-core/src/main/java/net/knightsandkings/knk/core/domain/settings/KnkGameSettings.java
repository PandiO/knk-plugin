package net.knightsandkings.knk.core.domain.settings;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The web API's global Game Settings ({@code GET /api/GameSettings}) as knk-plugin uses them
 * (docs/specs/game-settings/DESIGN.md). The runtime-world list is not kept: the plugin is its
 * source, not its reader.
 *
 * @param joinSpawnMode        {@code "WorldSpawn"} or {@code "CustomReference"}
 * @param joinSpawnReference   the configured spawn point; only meaningful in {@code CustomReference} mode
 * @param joinAnnouncement     broadcast on join with {@code {player}} filled in; null = the API sent none (the
 *                             plugin's default is used), blank = no broadcast
 * @param leaveAnnouncement    broadcast on quit, same rules
 * @param defaultRespawnPolicy for a world without its own block; never null
 * @param worldSettings        one per world; never null
 * @param updatedAt            the API's last-edit timestamp as sent, informational
 * @param motd                 server-list MOTD ({@code &}-coded, up to two lines); null = server.properties
 * @param groupOverrides       per-group overrides in precedence order (KNG-52); never null
 */
public record KnkGameSettings(String joinSpawnMode, KnkSpawnReference joinSpawnReference, String joinAnnouncement,
                              String leaveAnnouncement, KnkRespawnPolicy defaultRespawnPolicy,
                              List<KnkWorldSettings> worldSettings, String updatedAt, String motd,
                              List<KnkGroupOverride> groupOverrides) {

    public static final String WORLD_SPAWN = "WorldSpawn";
    public static final String CUSTOM_REFERENCE = "CustomReference";

    public KnkGameSettings {
        defaultRespawnPolicy = defaultRespawnPolicy != null ? defaultRespawnPolicy : KnkRespawnPolicy.worldSpawn();
        worldSettings = worldSettings == null ? List.of()
            : worldSettings.stream().filter(Objects::nonNull).filter(w -> w.worldName() != null).toList();
        groupOverrides = groupOverrides == null ? List.of()
            : groupOverrides.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(KnkGroupOverride::precedence)).toList();
    }

    /** Without MOTD and group overrides (the first KNG-52 round). */
    public KnkGameSettings(String joinSpawnMode, KnkSpawnReference joinSpawnReference, String joinAnnouncement,
                           String leaveAnnouncement, KnkRespawnPolicy defaultRespawnPolicy,
                           List<KnkWorldSettings> worldSettings, String updatedAt) {
        this(joinSpawnMode, joinSpawnReference, joinAnnouncement, leaveAnnouncement, defaultRespawnPolicy, worldSettings,
            updatedAt, null, List.of());
    }

    /** Only the join spawn (all {@code /spawn} reads); everything else at its default. */
    public KnkGameSettings(String joinSpawnMode, KnkSpawnReference joinSpawnReference) {
        this(joinSpawnMode, joinSpawnReference, null, null, null, List.of(), null);
    }

    /** The override for {@code groupId}, if the Game Settings page has one. */
    public Optional<KnkGroupOverride> groupOverride(int groupId) {
        return groupOverrides.stream().filter(o -> o.permissionGroupId() == groupId).findFirst();
    }

    /** The server spawn set on the Game Settings page, or empty when the main world's spawn is used. */
    public Optional<KnkSpawnReference> customJoinSpawn() {
        return CUSTOM_REFERENCE.equalsIgnoreCase(joinSpawnMode) ? Optional.ofNullable(joinSpawnReference) : Optional.empty();
    }

    /** The block for {@code worldName}, case-insensitive. */
    public Optional<KnkWorldSettings> world(String worldName) {
        if (worldName == null) {
            return Optional.empty();
        }
        return worldSettings.stream().filter(w -> w.worldName().equalsIgnoreCase(worldName)).findFirst();
    }

    /** The respawn policy for a death in {@code worldName}: the world's own, else the default. */
    public KnkRespawnPolicy respawnPolicyFor(String worldName) {
        return world(worldName).map(KnkWorldSettings::respawnPolicy).orElse(defaultRespawnPolicy);
    }
}
