package net.knightsandkings.knk.core.domain.settings;

import java.util.Optional;

/**
 * The parts of the web API's global Game Settings ({@code GET /api/GameSettings}) the plugin reads.
 *
 * @param joinSpawnMode      {@code "WorldSpawn"} or {@code "CustomReference"}
 * @param joinSpawnReference the configured spawn point; only meaningful in {@code CustomReference} mode
 */
public record KnkGameSettings(String joinSpawnMode, KnkSpawnReference joinSpawnReference) {

    public static final String WORLD_SPAWN = "WorldSpawn";
    public static final String CUSTOM_REFERENCE = "CustomReference";

    /** The server spawn set on the Game Settings page, or empty when the main world's spawn is used. */
    public Optional<KnkSpawnReference> customJoinSpawn() {
        return CUSTOM_REFERENCE.equalsIgnoreCase(joinSpawnMode) ? Optional.ofNullable(joinSpawnReference) : Optional.empty();
    }
}
