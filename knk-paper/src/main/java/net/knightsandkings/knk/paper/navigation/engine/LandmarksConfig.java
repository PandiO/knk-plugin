package net.knightsandkings.knk.paper.navigation.engine;

import org.bukkit.configuration.ConfigurationSection;

/**
 * {@code navigation.landmarks} in config.yml (off-road navigation phase 5.1, PandiO/knk-workspace#708, DESIGN § 6.5):
 * whether the navigation model loads the landmarks of {@code api/landmarks}, the destination engine offers them
 * ({@code LandmarkProvider}, only while {@code navigation.destination-engine} is on as well) and {@code /knk landmark}
 * works. Default {@code false}: nothing about landmarks runs. Read once at start-up (a change needs a restart).
 *
 * @param enabled landmarks are loaded, offered and editable in game
 */
public record LandmarksConfig(boolean enabled) {

    public static final String KEY = "navigation.landmarks";

    public static LandmarksConfig defaults() {
        return new LandmarksConfig(false);
    }

    /** From the root of config.yml; a missing key or section means off. */
    public static LandmarksConfig from(ConfigurationSection root) {
        return root == null ? defaults() : new LandmarksConfig(root.getBoolean(KEY, false));
    }
}
