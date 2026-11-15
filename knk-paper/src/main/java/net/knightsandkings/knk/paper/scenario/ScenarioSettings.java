package net.knightsandkings.knk.paper.scenario;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * {@code testing.scenarios.*} in {@code config.yml} (PandiO/knk-workspace#421). Off by default: only a
 * test server (the CI boot test, a developer's local server) turns it on. Read once at startup.
 *
 * @param enabled whether {@code /knk scenario} is registered at all
 * @param world   the world scenes are built in; blank means the main world
 * @param originX x of the first scene's lowest corner
 * @param originY y of every scene's lowest corner
 * @param originZ z of every scene's lowest corner
 */
public record ScenarioSettings(boolean enabled, String world, int originX, int originY, int originZ) {

    public static final String PATH = "testing.scenarios";
    public static final String ENABLED_PATH = PATH + ".enabled";

    /** Scenes sit side by side along x, this many blocks apart. */
    public static final int SPACING = 64;

    public static final ScenarioSettings DEFAULTS = new ScenarioSettings(false, "", 30_000, 100, 30_000);

    public static ScenarioSettings from(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection(PATH);
        if (section == null) {
            return DEFAULTS;
        }
        return new ScenarioSettings(
            section.getBoolean("enabled", DEFAULTS.enabled()),
            section.getString("world", DEFAULTS.world()),
            section.getInt("origin.x", DEFAULTS.originX()),
            section.getInt("origin.y", DEFAULTS.originY()),
            section.getInt("origin.z", DEFAULTS.originZ()));
    }
}
