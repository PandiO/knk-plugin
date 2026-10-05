package net.knightsandkings.knk.core.domain.settings;

import java.util.Locale;

/**
 * Where a player who died in a world comes back (knk-web-api {@code RespawnPolicyDto}; applied as in
 * docs/specs/game-settings/DESIGN.md §3.3).
 *
 * @param mode                   never null
 * @param reference              {@link Mode#CONFIGURED_REFERENCE}: the Location/Town/District/Structure
 * @param maxNearestTownDistance {@link Mode#NEAREST_TOWN}: towns further than this (blocks) don't count; null or
 *                               0 means any distance
 * @param useWorldSpawnFallback  when the mode finds no spot: true sends the player to the world's spawn,
 *                               false leaves the respawn to the server (bed, anchor or world spawn)
 */
public record KnkRespawnPolicy(Mode mode, KnkSpawnReference reference, Double maxNearestTownDistance,
                               boolean useWorldSpawnFallback) {

    public enum Mode {
        /** The server decides: bed or respawn anchor, else the world's spawn. */
        WORLD_SPAWN,
        /** A fixed Location, or a Town/District/Structure's Location. */
        CONFIGURED_REFERENCE,
        /** The town nearest to where the player died, in the same world. */
        NEAREST_TOWN,
        /** Synced with the join spawn: where the player would join (and {@code /spawn}), group override included. */
        JOIN_SPAWN;

        /** The API's value ({@code "WorldSpawn"}, {@code "ConfiguredReference"}, {@code "NearestTown"}, {@code "JoinSpawn"}), any case. */
        public static Mode parse(String value) {
            if (value == null || value.isBlank()) {
                return WORLD_SPAWN;
            }
            String squashed = value.replace("_", "").replace("-", "").trim().toUpperCase(Locale.ROOT);
            for (Mode mode : values()) {
                if (mode.name().replace("_", "").equals(squashed)) {
                    return mode;
                }
            }
            return WORLD_SPAWN;
        }
    }

    public KnkRespawnPolicy {
        mode = mode != null ? mode : Mode.WORLD_SPAWN;
    }

    /** The API's default policy: the server decides, world spawn as the fallback. */
    public static KnkRespawnPolicy worldSpawn() {
        return new KnkRespawnPolicy(Mode.WORLD_SPAWN, null, null, true);
    }
}
