package net.knightsandkings.knk.core.domain.settings;

/**
 * A loaded world as knk-plugin reports it ({@code PUT /api/GameSettings/runtime-worlds}, knk-web-api
 * {@code MinecraftWorldRuntimeDto}); the Game Settings page lists these and adds a settings block for each.
 *
 * @param primary the server's main world (Bukkit's first world)
 */
public record KnkWorldRuntime(String worldName, String folderName, String environment, boolean loaded,
                              int playerCount, boolean primary) {
}
